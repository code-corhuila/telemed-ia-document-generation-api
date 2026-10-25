package co.edu.corhuila.telemedia.documentgeneration.adapter.in.background;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/** DEC-008: the outbox is published from startup on, then every interval, whatever happens. */
class OutboxRelayTest {

    private static final Duration INTERVAL = Duration.ofSeconds(5);

    private final Deque<Runnable> scheduled = new ArrayDeque<>();
    private final List<Duration> delays = new ArrayList<>();
    private final OutboxRelay.Scheduler scheduler = (task, delay) -> {
        scheduled.add(task);
        delays.add(delay);
    };

    private void runNext() {
        scheduled.poll().run();
    }

    @Test
    void publishesRightAwayAndThenEveryInterval() {
        AtomicInteger rounds = new AtomicInteger();
        OutboxRelay relay = new OutboxRelay(() -> {
            rounds.incrementAndGet();
            return 1;
        }, INTERVAL, scheduler);

        relay.start();
        runNext();
        runNext();

        assertThat(rounds).hasValue(2);
        assertThat(delays).containsExactly(Duration.ZERO, INTERVAL, INTERVAL);
    }

    @Test
    void anUnexpectedErrorDoesNotStopTheRelay() {
        AtomicInteger rounds = new AtomicInteger();
        OutboxRelay relay = new OutboxRelay(() -> {
            if (rounds.incrementAndGet() == 1) {
                throw new IllegalStateException("database unavailable");
            }
            return 0;
        }, INTERVAL, scheduler);

        relay.start();
        runNext();
        runNext();

        assertThat(rounds).hasValue(2);
        assertThat(scheduled).hasSize(1);
    }

    @Test
    void theIntervalMustBePositive() {
        assertThatThrownBy(() -> new OutboxRelay(() -> 0, Duration.ZERO, scheduler))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
