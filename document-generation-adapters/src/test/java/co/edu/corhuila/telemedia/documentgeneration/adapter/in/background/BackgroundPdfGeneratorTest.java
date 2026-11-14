package co.edu.corhuila.telemedia.documentgeneration.adapter.in.background;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.test.simple.SimpleSpan;
import io.micrometer.tracing.test.simple.SimpleTracer;

import co.edu.corhuila.telemedia.documentgeneration.adapter.in.background.BackgroundPdfGenerator.Settings;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DocumentPdfGeneration;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.GenerationAttempt;

/**
 * The scheduler is manual: tasks run only when the test says so, and their delays are
 * recorded, so retries, backoff and the resume sweep are checked without waiting.
 */
class BackgroundPdfGeneratorTest {

    private static final Settings SETTINGS = new Settings(3, 10, Duration.ofSeconds(60), Duration.ofSeconds(2),
            Duration.ofSeconds(5));

    private final FakeGeneration generation = new FakeGeneration();
    private final ManualScheduler scheduler = new ManualScheduler();
    private int correlations;
    private double random = 1.0;
    private final BackgroundPdfGenerator generator = new BackgroundPdfGenerator(generation, SETTINGS, scheduler,
            () -> random, () -> "run-" + ++correlations);
    private final SimpleTracer tracer = new SimpleTracer();
    private final Logger logger = (Logger) LoggerFactory.getLogger(BackgroundPdfGenerator.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final BackgroundPdfGenerator tracedGenerator = new BackgroundPdfGenerator(generation, SETTINGS,
            scheduler, () -> random, () -> "run-" + ++correlations, tracer);

    @BeforeEach
    void captureLogs() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
    }

    @Test
    void aSubmittedDocumentIsGeneratedRightAwayWithItsCorrelationId() {
        UUID document = UUID.randomUUID();

        assertThat(generator.submit(document, "trace-1")).isTrue();
        assertThat(scheduler.delays()).containsExactly(Duration.ZERO);
        scheduler.runAll();

        assertThat(generation.calls).containsExactly(new Call(document, "trace-1"));
        assertThat(generator.inFlight()).isZero();
    }

    @Test
    void aRetryableFailureIsRetriedWithExponentialBackoffAndFullJitter() {
        UUID document = UUID.randomUUID();
        generation.outcomes(document, GenerationAttempt.RETRY, GenerationAttempt.RETRY, GenerationAttempt.RETRY,
                GenerationAttempt.AVAILABLE);

        generator.submit(document, "trace-1");
        scheduler.runAll();

        // 2 s doubling, capped at 5 s; full jitter with random = 1 gives the ceiling.
        assertThat(scheduler.history).containsExactly(Duration.ZERO, Duration.ofSeconds(2), Duration.ofSeconds(4),
                Duration.ofSeconds(5));
        assertThat(generation.calls).hasSize(4).allSatisfy(call -> assertThat(call.correlationId()).isEqualTo("trace-1"));
        assertThat(generator.inFlight()).isZero();
    }

    @Test
    void theJitterScalesTheWait() {
        UUID document = UUID.randomUUID();
        random = 0.25;
        generation.outcomes(document, GenerationAttempt.RETRY, GenerationAttempt.AVAILABLE);

        generator.submit(document, "trace-1");
        scheduler.runAll();

        assertThat(scheduler.history).containsExactly(Duration.ZERO, Duration.ofMillis(500));
    }

    @Test
    void finalOutcomesAreNotRetried() {
        for (GenerationAttempt outcome : List.of(GenerationAttempt.AVAILABLE, GenerationAttempt.FAILED,
                GenerationAttempt.SKIPPED)) {
            UUID document = UUID.randomUUID();
            generation.outcomes(document, outcome);

            generator.submit(document, "trace-1");
            scheduler.runAll();

            assertThat(generation.callsFor(document)).as(outcome.name()).isEqualTo(1);
        }
        assertThat(generator.inFlight()).isZero();
    }

    @Test
    void aDocumentAlreadyInFlightIsNotSubmittedTwice() {
        UUID document = UUID.randomUUID();
        generator.submit(document, "trace-1");

        assertThat(generator.submit(document, "trace-2")).isFalse();
        scheduler.runAll();

        assertThat(generation.calls).containsExactly(new Call(document, "trace-1"));
    }

    @Test
    void aDocumentWaitingForItsRetryIsStillInFlight() {
        UUID document = UUID.randomUUID();
        generation.outcomes(document, GenerationAttempt.RETRY, GenerationAttempt.AVAILABLE);
        generator.submit(document, "trace-1");
        scheduler.runNext();

        assertThat(generator.submit(document, "trace-2")).isFalse();
        assertThat(generator.inFlight()).isEqualTo(1);
    }

    @Test
    void overTheInFlightLimitASubmissionIsLeftForTheResumeSweep() {
        for (int i = 0; i < SETTINGS.maxInFlight(); i++) {
            assertThat(generator.submit(UUID.randomUUID(), "trace")).isTrue();
        }

        assertThat(generator.submit(UUID.randomUUID(), "trace")).isFalse();
        assertThat(scheduler.pending()).isEqualTo(SETTINGS.maxInFlight());
    }

    @Test
    void anAttemptThatThrowsIsReleasedForTheNextSweep() {
        UUID document = UUID.randomUUID();
        generation.failure = new IllegalStateException("database down");

        generator.submit(document, "trace-1");
        scheduler.runAll();

        assertThat(generator.inFlight()).isZero();
        generation.failure = null;
        assertThat(generator.submit(document, "trace-2")).isTrue();
    }

    @Test
    void startingSweepsAtOnceAndThenEveryInterval() {
        generator.start();

        assertThat(scheduler.delays()).containsExactly(Duration.ZERO);
        scheduler.runNext();

        assertThat(generation.resumableLimits).containsExactly(SETTINGS.resumeBatchSize());
        assertThat(scheduler.delays()).containsExactly(SETTINGS.resumeInterval());
    }

    @Test
    void theSweepSubmitsResumableDocumentsEachWithItsOwnRunCorrelationId() {
        UUID pending = UUID.randomUUID();
        UUID failed = UUID.randomUUID();
        generation.resumable = List.of(pending, failed);

        generator.start();
        scheduler.runNext();
        scheduler.runAllExcept(SETTINGS.resumeInterval());

        assertThat(generation.calls).containsExactlyInAnyOrder(new Call(pending, "run-1"), new Call(failed, "run-2"));
    }

    @Test
    void theSweepSkipsDocumentsAlreadyInFlight() {
        UUID document = UUID.randomUUID();
        generation.resumable = List.of(document);
        generator.start();
        generator.submit(document, "trace-1");

        scheduler.runNext();
        scheduler.runAllExcept(SETTINGS.resumeInterval());

        assertThat(generation.calls).containsExactly(new Call(document, "trace-1"));
    }

    @Test
    void aFailingSweepKeepsSweeping() {
        generation.resumableFailure = new IllegalStateException("database down");

        generator.start();
        scheduler.runNext();

        assertThat(scheduler.delays()).containsExactly(SETTINGS.resumeInterval());
    }

    @Test
    void eachAttemptIsAChildSpanOfTheRequestThatSubmittedIt() {
        UUID document = UUID.randomUUID();
        generation.outcomes(document, GenerationAttempt.RETRY, GenerationAttempt.AVAILABLE);
        Span request = tracer.nextSpan().name("http post /api/documents/generate").start();
        try (Tracer.SpanInScope scope = tracer.withSpan(request)) {
            tracedGenerator.submit(document, "trace-1");
        }
        request.end();

        scheduler.runAll();

        List<SimpleSpan> attempts = attempts();
        assertThat(attempts).hasSize(2).allSatisfy(attempt -> {
            assertThat(attempt.getTraceId()).isEqualTo(request.context().traceId());
            assertThat(attempt.getParentId()).isEqualTo(request.context().spanId());
            assertThat(attempt.getTags()).containsEntry("document.id", document.toString());
            assertThat(attempt.getEndTimestamp()).isAfterOrEqualTo(attempt.getStartTimestamp());
        });
        assertThat(attempts).extracting(attempt -> attempt.getTags().get("outcome")).containsExactly("retry",
                "available");
    }

    @Test
    void theAttemptSpanIsCurrentWhileTheDocumentIsGenerated() {
        generation.tracer = tracer;

        tracedGenerator.submit(UUID.randomUUID(), "trace-1");
        scheduler.runAll();

        assertThat(generation.spansSeen).containsExactly(attempts().get(0).getSpanId());
        assertThat(tracer.currentSpan()).isNull();
    }

    @Test
    void aDocumentResumedBySweepStartsItsOwnTrace() {
        generation.resumable = List.of(UUID.randomUUID());

        tracedGenerator.start();
        scheduler.runNext();
        scheduler.runAllExcept(SETTINGS.resumeInterval());

        assertThat(attempts()).singleElement().satisfies(attempt -> assertThat(attempt.getParentId()).isNullOrEmpty());
    }

    @Test
    void anAttemptThatThrowsEndsItsSpanWithTheError() {
        generation.failure = new IllegalStateException("database down");

        tracedGenerator.submit(UUID.randomUUID(), "trace-1");
        scheduler.runAll();

        assertThat(attempts()).singleElement().satisfies(attempt -> {
            assertThat(attempt.getError()).isSameAs(generation.failure);
            assertThat(attempt.getEndTimestamp()).isAfterOrEqualTo(attempt.getStartTimestamp());
        });
    }

    @Test
    void eachAttemptLogsItsStartAndItsOutcomeAsDomainEvents() {
        UUID document = UUID.randomUUID();
        generation.outcomes(document, GenerationAttempt.RETRY, GenerationAttempt.AVAILABLE);

        generator.submit(document, "trace-1");
        scheduler.runAll();

        List<Map<String, Object>> events = events();
        assertThat(events).extracting(event -> event.get("event")).containsExactly("document.generation.started",
                "document.generation.retry_scheduled", "document.generation.started", "document.generation.available");
        assertThat(events).extracting(event -> event.get("status")).containsExactly("GENERATING", "ERROR",
                "GENERATING", "AVAILABLE");
        assertThat(events).extracting(event -> event.get("attempt")).containsExactly(1, 1, 2, 2);
        assertThat(events).allSatisfy(event -> assertThat(event).containsEntry("document_id", document.toString()));
        assertThat(events.get(1).get("duration_ms")).isInstanceOf(Long.class);
        assertThat(events.get(3).get("duration_ms")).isInstanceOf(Long.class);
    }

    @Test
    void aDocumentWithNoRetryLeftLogsFailedAsAWarning() {
        UUID document = UUID.randomUUID();
        generation.outcomes(document, GenerationAttempt.FAILED);

        generator.submit(document, "trace-1");
        scheduler.runAll();

        ILoggingEvent failed = logs.list.get(logs.list.size() - 1);
        assertThat(failed.getLevel()).isEqualTo(Level.WARN);
        assertThat(events().get(1)).containsEntry("event", "document.generation.failed").containsEntry("status", "ERROR")
                .containsKey("duration_ms");
    }

    @Test
    void skippedAndInterruptedAttemptsAreLoggedWithoutAGuessedStatus() {
        UUID skipped = UUID.randomUUID();
        generation.outcomes(skipped, GenerationAttempt.SKIPPED);
        generator.submit(skipped, "trace-1");
        scheduler.runAll();
        generation.failure = new IllegalStateException("database down");
        generator.submit(UUID.randomUUID(), "trace-2");
        scheduler.runAll();

        assertThat(events()).extracting(event -> event.get("event")).containsExactly("document.generation.started",
                "document.generation.skipped", "document.generation.started", "document.generation.interrupted");
        assertThat(events().get(1)).doesNotContainKey("status");
        assertThat(events().get(3)).doesNotContainKey("status");
    }

    @Test
    void domainEventsCarryOnlyIdentifiersStateAndTiming() {
        generation.outcomes(UUID.randomUUID(), GenerationAttempt.RETRY);

        generator.submit(UUID.randomUUID(), "trace-1");
        scheduler.runAll();

        assertThat(events()).isNotEmpty().allSatisfy(event -> assertThat(event.keySet())
                .isSubsetOf(Set.of("event", "document_id", "status", "duration_ms", "attempt")));
    }

    @Test
    void whilePausedASubmissionIsLeftPendingAndNoAttemptRuns() {
        generator.pause();

        assertThat(generator.submit(UUID.randomUUID(), "trace-1")).isFalse();

        assertThat(scheduler.pending()).isZero();
        assertThat(generation.calls).isEmpty();
        assertThat(generator.isPaused()).isTrue();
    }

    @Test
    void aPauseStopsQueuedAttemptsAndWaitingRetriesAndReleasesThemForTheSweep() {
        UUID retrying = UUID.randomUUID();
        UUID queued = UUID.randomUUID();
        generation.outcomes(retrying, GenerationAttempt.RETRY, GenerationAttempt.AVAILABLE);
        generator.submit(retrying, "trace-1");
        scheduler.runNext();
        generator.submit(queued, "trace-2");

        generator.pause();
        scheduler.runAll();

        assertThat(generation.calls).containsExactly(new Call(retrying, "trace-1"));
        assertThat(generator.inFlight()).isZero();
        assertThat(events()).extracting(event -> event.get("event")).endsWith("document.generation.deferred",
                "document.generation.deferred");
    }

    @Test
    void whilePausedTheSweepDoesNotLookForDocumentsButKeepsItsSchedule() {
        generation.resumable = List.of(UUID.randomUUID());
        generator.pause();

        generator.start();
        scheduler.runNext();

        assertThat(generation.resumableLimits).isEmpty();
        assertThat(scheduler.delays()).containsExactly(SETTINGS.resumeInterval());
    }

    @Test
    void resumingSweepsAtOnceSoEveryWaitingDocumentIsPickedUp() {
        UUID waiting = UUID.randomUUID();
        generation.resumable = List.of(waiting);
        generator.pause();

        generator.resume();
        scheduler.runAll();

        assertThat(generator.isPaused()).isFalse();
        assertThat(generation.calls).containsExactly(new Call(waiting, "run-1"));
        // A one-off sweep: the periodic one is started only by start().
        assertThat(scheduler.pending()).isZero();
    }

    @Test
    void resumingWhenNotPausedChangesNothing() {
        generator.resume();

        assertThat(scheduler.pending()).isZero();
    }

    /** The key-value pairs of each log line that names a domain event. */
    private List<Map<String, Object>> events() {
        return logs.list.stream().filter(line -> line.getKeyValuePairs() != null).map(line -> {
            Map<String, Object> fields = new LinkedHashMap<>();
            line.getKeyValuePairs().forEach(pair -> fields.put(pair.key, pair.value));
            return fields;
        }).filter(fields -> fields.containsKey("event")).toList();
    }

    private List<SimpleSpan> attempts() {
        return tracer.getSpans().stream().filter(span -> "pdf generation attempt".equals(span.getName())).toList();
    }

    @Test
    void theSettingsAreValidated() {
        Duration second = Duration.ofSeconds(1);
        assertThatThrownBy(() -> new Settings(0, 10, second, second, second))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Settings(1, 0, second, second, second))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Settings(1, 10, Duration.ZERO, second, second))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Settings(1, 10, second, second, Duration.ofMillis(10)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private record Call(UUID documentId, String correlationId) { }

    private static final class FakeGeneration implements DocumentPdfGeneration {

        private final List<Call> calls = new ArrayList<>();
        private final Map<UUID, Deque<GenerationAttempt>> outcomes = new HashMap<>();
        private final List<Integer> resumableLimits = new ArrayList<>();
        private List<UUID> resumable = List.of();
        private RuntimeException failure;
        private RuntimeException resumableFailure;
        private Tracer tracer;
        private final List<String> spansSeen = new ArrayList<>();

        void outcomes(UUID document, GenerationAttempt... sequence) {
            outcomes.put(document, new ArrayDeque<>(List.of(sequence)));
        }

        long callsFor(UUID document) {
            return calls.stream().filter(call -> call.documentId().equals(document)).count();
        }

        @Override
        public GenerationAttempt generate(UUID documentId, String correlationId) {
            calls.add(new Call(documentId, correlationId));
            if (tracer != null) {
                spansSeen.add(tracer.currentSpan().context().spanId());
            }
            if (failure != null) {
                throw failure;
            }
            Deque<GenerationAttempt> queue = outcomes.get(documentId);
            return queue == null || queue.isEmpty() ? GenerationAttempt.AVAILABLE : queue.poll();
        }

        @Override
        public List<UUID> resumable(int limit) {
            resumableLimits.add(limit);
            if (resumableFailure != null) {
                throw resumableFailure;
            }
            return resumable;
        }
    }

    /** Runs scheduled tasks on demand, in order, and remembers every delay asked for. */
    private static final class ManualScheduler implements BackgroundPdfGenerator.Scheduler {

        private record Task(Runnable runnable, Duration delay) { }

        private final Deque<Task> tasks = new ArrayDeque<>();
        private final List<Duration> history = new ArrayList<>();

        @Override
        public void schedule(Runnable task, Duration delay) {
            tasks.add(new Task(task, delay));
            history.add(delay);
        }

        List<Duration> delays() {
            return tasks.stream().map(Task::delay).toList();
        }

        int pending() {
            return tasks.size();
        }

        void runNext() {
            tasks.poll().runnable().run();
        }

        void runAll() {
            while (!tasks.isEmpty()) {
                runNext();
            }
        }

        /** Runs everything except tasks with the given delay (the periodic sweep). */
        void runAllExcept(Duration delay) {
            Task next;
            while ((next = tasks.stream().filter(task -> !task.delay().equals(delay)).findFirst().orElse(null)) != null) {
                tasks.remove(next);
                next.runnable().run();
            }
        }
    }
}
