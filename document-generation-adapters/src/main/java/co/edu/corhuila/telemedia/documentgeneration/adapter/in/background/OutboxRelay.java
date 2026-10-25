package co.edu.corhuila.telemedia.documentgeneration.adapter.in.background;

import java.time.Duration;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import co.edu.corhuila.telemedia.documentgeneration.application.port.in.OutboxPublication;

/**
 * DEC-008: publishes the outbox from startup on, then {@code interval} after each round.
 * A round that fails is logged and the next one runs anyway; the rows stay in the outbox.
 */
public final class OutboxRelay {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);

    /** Runs a task after a delay. */
    @FunctionalInterface
    public interface Scheduler {
        void schedule(Runnable task, Duration delay);
    }

    private final OutboxPublication publication;
    private final Duration interval;
    private final Scheduler scheduler;

    public OutboxRelay(OutboxPublication publication, Duration interval, Scheduler scheduler) {
        this.publication = Objects.requireNonNull(publication, "publication");
        this.interval = Objects.requireNonNull(interval, "interval");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        if (!interval.isPositive()) {
            throw new IllegalArgumentException("the publication interval must be positive");
        }
    }

    public void start() {
        scheduler.schedule(this::round, Duration.ZERO);
    }

    private void round() {
        try {
            int published = publication.publishPending();
            if (published > 0) {
                LOG.atInfo().addKeyValue("event", "outbox.published").addKeyValue("count", published)
                        .log("outbox events published");
            }
        } catch (RuntimeException e) {
            LOG.warn("outbox publication round failed: {}", e.getClass().getSimpleName());
        } finally {
            scheduler.schedule(this::round, interval);
        }
    }
}
