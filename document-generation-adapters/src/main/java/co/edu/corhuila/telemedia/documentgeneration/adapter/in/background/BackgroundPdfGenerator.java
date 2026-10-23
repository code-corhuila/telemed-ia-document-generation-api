package co.edu.corhuila.telemedia.documentgeneration.adapter.in.background;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import co.edu.corhuila.telemedia.documentgeneration.adapter.in.http.CorrelationFilter;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DocumentPdfGeneration;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.GenerationAttempt;

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
 * </ul>
 * Waiting retries live in memory only: after a restart the sweep finds them again.
 */
public final class BackgroundPdfGenerator implements PdfGenerationQueue {

    private static final Logger LOG = LoggerFactory.getLogger(BackgroundPdfGenerator.class);

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
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    public BackgroundPdfGenerator(DocumentPdfGeneration generation, Settings settings, Scheduler scheduler,
            DoubleSupplier random, Supplier<String> newCorrelationId) {
        this.generation = Objects.requireNonNull(generation, "generation");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.random = Objects.requireNonNull(random, "random");
        this.newCorrelationId = Objects.requireNonNull(newCorrelationId, "newCorrelationId");
    }

    /** Starts the resume sweep: now, then every {@code resumeInterval}. */
    public void start() {
        scheduler.schedule(this::sweep, Duration.ZERO);
    }

    @Override
    public boolean submit(UUID documentId, String correlationId) {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(correlationId, "correlationId");
        synchronized (inFlight) {
            if (inFlight.size() >= settings.maxInFlight() || !inFlight.add(documentId)) {
                return false;
            }
        }
        scheduler.schedule(() -> attempt(documentId, correlationId, 0), Duration.ZERO);
        return true;
    }

    /** Documents queued or waiting for a retry: the saturation of the generation. */
    public int inFlight() {
        return inFlight.size();
    }

    private void attempt(UUID documentId, String correlationId, int retries) {
        MDC.put(CorrelationFilter.MDC_KEY, correlationId);
        try {
            GenerationAttempt outcome = generation.generate(documentId, correlationId);
            if (outcome == GenerationAttempt.RETRY) {
                int retry = retries + 1;
                scheduler.schedule(() -> attempt(documentId, correlationId, retry), backoff(retry));
                return;
            }
            if (outcome == GenerationAttempt.FAILED) {
                LOG.warn("PDF generation of document {} failed with no retry left", documentId);
            }
            inFlight.remove(documentId);
        } catch (RuntimeException e) {
            // The document keeps its stored state; the next sweep resumes it.
            LOG.error("PDF generation attempt of document {} stopped unexpectedly", documentId, e);
            inFlight.remove(documentId);
        } finally {
            MDC.remove(CorrelationFilter.MDC_KEY);
        }
    }

    /** Full jitter: a random wait between zero and the capped exponential backoff. */
    private Duration backoff(int retry) {
        long base = settings.backoffBase().toMillis();
        long ceiling = Math.min(settings.backoffCap().toMillis(), base << Math.min(retry - 1, 30));
        return Duration.ofMillis((long) (random.getAsDouble() * ceiling));
    }

    private void sweep() {
        try {
            List<UUID> resumable = generation.resumable(settings.resumeBatchSize());
            for (UUID documentId : resumable) {
                if (!inFlight.contains(documentId)) {
                    submit(documentId, newCorrelationId.get());
                }
            }
        } catch (RuntimeException e) {
            LOG.error("the resume sweep of PDF generation failed; it runs again later", e);
        } finally {
            scheduler.schedule(this::sweep, settings.resumeInterval());
        }
    }
}
