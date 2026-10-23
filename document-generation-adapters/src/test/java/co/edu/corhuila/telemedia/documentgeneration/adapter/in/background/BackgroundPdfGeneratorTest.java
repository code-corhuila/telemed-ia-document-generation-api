package co.edu.corhuila.telemedia.documentgeneration.adapter.in.background;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

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

        void outcomes(UUID document, GenerationAttempt... sequence) {
            outcomes.put(document, new ArrayDeque<>(List.of(sequence)));
        }

        long callsFor(UUID document) {
            return calls.stream().filter(call -> call.documentId().equals(document)).count();
        }

        @Override
        public GenerationAttempt generate(UUID documentId, String correlationId) {
            calls.add(new Call(documentId, correlationId));
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
