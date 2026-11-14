package co.edu.corhuila.telemedia.documentgeneration.adapter.in.background;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;

import co.edu.corhuila.telemedia.documentgeneration.adapter.in.http.CorrelationFilter;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DocumentPdfGeneration;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.GenerationAttempt;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

/**
 * Drives {@link DocumentPdfGeneration} in the background (DEC-006):
 * <ul>
 *   <li>a submitted document gets its attempt at once; a retryable failure is attempted
 *       again after an exponential backoff with full jitter (norma 5.7.2, DEC-005), and the
 *       use case decides when no retry is left;</li>
 *   <li>at most {@code maxInFlight} documents are queued or waiting for a retry; beyond
 *       that a submission is refused and the document stays in the database;</li>
 *   <li>a sweep at startup and then every {@code resumeInterval} queues the documents left
 *       PENDING, GENERATING or retryable ERROR (a restart, a refused submission, an attempt
 *       that threw), each run with its own correlation id (norma 5.7.3).</li>
 *   <li>each attempt is a span; one submitted during a request is a child of the request's
 *       span, so the whole generation shows in one trace (OBS-DG-02).</li>
 *   <li>each attempt logs its start and its outcome as domain events, with the fields of the
 *       log contract: {@code event}, {@code document_id}, {@code status}, {@code duration_ms}
 *       and the {@code attempt} number; never clinical content (OBS-DG-03).</li>
 *   <li>generation can be paused without stopping the service, while a dependency is down
 *       (OBS-DG-06): no attempt runs, documents wait in their stored state without spending
 *       retries, and resuming sweeps at once.</li>
 * </ul>
 * Waiting retries live in memory only: after a restart the sweep finds them again.
 */
public final class BackgroundPdfGenerator implements PdfGenerationQueue {

    private static final Logger LOG = LoggerFactory.getLogger(BackgroundPdfGenerator.class);
    static final String SPAN_NAME = "pdf generation attempt";

    /**
     * @param backoffBase wait before the first retry, doubled on each one up to {@code backoffCap}
     */
    public record Settings(int maxInFlight, int resumeBatchSize, Duration resumeInterval, Duration backoffBase,
            Duration backoffCap) {

        public Settings {
            Objects.requireNonNull(resumeInterval, "resumeInterval");
            Objects.requireNonNull(backoffBase, "backoffBase");
            Objects.requireNonNull(backoffCap, "backoffCap");
            if (maxInFlight < 1 || resumeBatchSize < 1) {
                throw new IllegalArgumentException("the in-flight limit and the resume batch must be positive");
            }
            if (!resumeInterval.isPositive() || !backoffBase.isPositive()) {
                throw new IllegalArgumentException("the resume interval and the backoff must be positive");
            }
            if (backoffCap.compareTo(backoffBase) < 0) {
                throw new IllegalArgumentException("the backoff cap must not be below its base");
            }
        }
    }

    /** Runs a task after a delay, on a bounded pool of threads. */
    @FunctionalInterface
    public interface Scheduler {
        void schedule(Runnable task, Duration delay);
    }

    private final DocumentPdfGeneration generation;
    private final Settings settings;
    private final Scheduler scheduler;
    private final DoubleSupplier random;
    private final Supplier<String> newCorrelationId;
    private final Tracer tracer;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean paused = new AtomicBoolean();

    public BackgroundPdfGenerator(DocumentPdfGeneration generation, Settings settings, Scheduler scheduler,
            DoubleSupplier random, Supplier<String> newCorrelationId) {
        this(generation, settings, scheduler, random, newCorrelationId, Tracer.NOOP);
    }

    public BackgroundPdfGenerator(DocumentPdfGeneration generation, Settings settings, Scheduler scheduler,
            DoubleSupplier random, Supplier<String> newCorrelationId, Tracer tracer) {
        this.generation = Objects.requireNonNull(generation, "generation");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.random = Objects.requireNonNull(random, "random");
        this.newCorrelationId = Objects.requireNonNull(newCorrelationId, "newCorrelationId");
        this.tracer = Objects.requireNonNull(tracer, "tracer");
    }

    /** Starts the resume sweep: now, then every {@code resumeInterval}. */
    public void start() {
        scheduler.schedule(this::sweep, Duration.ZERO);
    }

    @Override
    public boolean submit(UUID documentId, String correlationId) {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(correlationId, "correlationId");
        if (paused.get()) {
            return false;
        }
        synchronized (inFlight) {
            if (inFlight.size() >= settings.maxInFlight() || !inFlight.add(documentId)) {
                return false;
            }
        }
        Span parent = tracer.currentSpan();
        scheduler.schedule(() -> attempt(documentId, correlationId, 0, parent), Duration.ZERO);
        return true;
    }

    /** Documents queued or waiting for a retry: the saturation of the generation. */
    public int inFlight() {
        return inFlight.size();
    }

    /**
     * Stops new attempts. Queued attempts and waiting retries are dropped when their turn
     * comes; the documents keep their stored state for the sweep after {@link #resume()}.
     */
    public void pause() {
        if (!paused.getAndSet(true)) {
            LOG.warn("PDF generation paused");
        }
    }

    /** Lets attempts run again and sweeps at once for the documents that waited. */
    public void resume() {
        if (paused.getAndSet(false)) {
            LOG.info("PDF generation resumed");
            scheduler.schedule(this::resumeWaiting, Duration.ZERO);
        }
    }

    public boolean isPaused() {
        return paused.get();
    }

    /** @param parent span of the request that submitted the document; {@code null} for the sweep */
    private void attempt(UUID documentId, String correlationId, int retries, Span parent) {
        if (paused.get()) {
            // Dropped without an attempt, so no retry is spent; the sweep resumes it later.
            inFlight.remove(documentId);
            event(Level.INFO, "document.generation.deferred", documentId, retries + 1)
                    .log("PDF generation of document {} deferred: generation is paused", documentId);
            return;
        }
        Span span = (parent == null ? tracer.nextSpan() : tracer.nextSpan(parent)).name(SPAN_NAME)
                .tag("document.id", documentId.toString()).start();
        MDC.put(CorrelationFilter.MDC_KEY, correlationId);
        int number = retries + 1;
        long start = System.nanoTime();
        try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
            event(Level.INFO, "document.generation.started", documentId, number).addKeyValue("status", "GENERATING")
                    .log("PDF generation attempt {} of document {} started", number, documentId);
            GenerationAttempt outcome = generation.generate(documentId, correlationId);
            span.tag("outcome", outcome.name().toLowerCase(Locale.ROOT));
            logOutcome(outcome, documentId, number, start);
            if (outcome == GenerationAttempt.RETRY) {
                int retry = retries + 1;
                scheduler.schedule(() -> attempt(documentId, correlationId, retry, parent), backoff(retry));
                return;
            }
            inFlight.remove(documentId);
        } catch (RuntimeException e) {
            // The document keeps its stored state; the next sweep resumes it.
            span.error(e);
            event(Level.ERROR, "document.generation.interrupted", documentId, number)
                    .addKeyValue("duration_ms", elapsedMillis(start)).setCause(e)
                    .log("PDF generation attempt {} of document {} stopped unexpectedly", number, documentId);
            inFlight.remove(documentId);
        } finally {
            span.end();
            MDC.remove(CorrelationFilter.MDC_KEY);
        }
    }

    private static void logOutcome(GenerationAttempt outcome, UUID documentId, int number, long start) {
        long duration = elapsedMillis(start);
        switch (outcome) {
            case AVAILABLE -> event(Level.INFO, "document.generation.available", documentId, number)
                    .addKeyValue("status", "AVAILABLE").addKeyValue("duration_ms", duration)
                    .log("PDF of document {} is available", documentId);
            case RETRY -> event(Level.INFO, "document.generation.retry_scheduled", documentId, number)
                    .addKeyValue("status", "ERROR").addKeyValue("duration_ms", duration)
                    .log("PDF generation attempt {} of document {} failed; it is retried", number, documentId);
            case FAILED -> event(Level.WARN, "document.generation.failed", documentId, number)
                    .addKeyValue("status", "ERROR").addKeyValue("duration_ms", duration)
                    .log("PDF generation of document {} failed with no retry left", documentId);
            // The document was no longer waiting for its PDF: its status is not known here.
            case SKIPPED -> event(Level.INFO, "document.generation.skipped", documentId, number)
                    .addKeyValue("duration_ms", duration)
                    .log("PDF generation of document {} was not needed", documentId);
        }
    }

    /** A log line of the log contract: only identifiers, state and timing. */
    private static LoggingEventBuilder event(Level level, String name, UUID documentId, int number) {
        return LOG.atLevel(level).addKeyValue("event", name).addKeyValue("document_id", documentId.toString())
                .addKeyValue("attempt", number);
    }

    private static long elapsedMillis(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    /** Full jitter: a random wait between zero and the capped exponential backoff. */
    private Duration backoff(int retry) {
        long base = settings.backoffBase().toMillis();
        long ceiling = Math.min(settings.backoffCap().toMillis(), base << Math.min(retry - 1, 30));
        return Duration.ofMillis((long) (random.getAsDouble() * ceiling));
    }

    private void sweep() {
        try {
            if (!paused.get()) {
                resumeWaiting();
            }
        } finally {
            scheduler.schedule(this::sweep, settings.resumeInterval());
        }
    }

    private void resumeWaiting() {
        try {
            List<UUID> resumable = generation.resumable(settings.resumeBatchSize());
            for (UUID documentId : resumable) {
                if (!inFlight.contains(documentId)) {
                    submit(documentId, newCorrelationId.get());
                }
            }
        } catch (RuntimeException e) {
            LOG.error("the resume sweep of PDF generation failed; it runs again later", e);
        }
    }
}
