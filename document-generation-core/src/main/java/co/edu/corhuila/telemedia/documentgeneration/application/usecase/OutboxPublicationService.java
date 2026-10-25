package co.edu.corhuila.telemedia.documentgeneration.application.usecase;

import java.util.Objects;

import co.edu.corhuila.telemedia.documentgeneration.application.port.in.OutboxPublication;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.EventPublicationException;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.EventPublisher;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.PendingEvents;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.PendingEvents.Outcome;

/**
 * DEC-008: the oldest pending events are published one by one. After a failure the rest of
 * the batch is not attempted, because the broker is most likely unavailable; those events
 * wait for the next round untouched.
 */
public final class OutboxPublicationService implements OutboxPublication {

    private final PendingEvents events;
    private final EventPublisher publisher;
    private final int batchSize;

    public OutboxPublicationService(PendingEvents events, EventPublisher publisher, int batchSize) {
        this.events = Objects.requireNonNull(events, "events");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        if (batchSize < 1) {
            throw new IllegalArgumentException("the batch size must be positive");
        }
        this.batchSize = batchSize;
    }

    @Override
    public int publishPending() {
        int[] published = {0};
        boolean[] failed = {false};
        events.publishOldest(batchSize, event -> {
            if (failed[0]) {
                return Outcome.notAttempted();
            }
            try {
                publisher.publish(event);
                published[0]++;
                return Outcome.published();
            } catch (EventPublicationException e) {
                failed[0] = true;
                return Outcome.failed(e.getMessage());
            }
        });
        return published[0];
    }
}
