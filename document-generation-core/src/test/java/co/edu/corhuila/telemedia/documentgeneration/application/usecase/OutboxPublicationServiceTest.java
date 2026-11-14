package co.edu.corhuila.telemedia.documentgeneration.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import co.edu.corhuila.telemedia.documentgeneration.application.port.out.EventPublicationException;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.EventPublisher;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.PendingEvent;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.PendingEvents;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.PendingEvents.Outcome;

/** DEC-008: the pending outbox rows are published one by one, oldest first. */
class OutboxPublicationServiceTest {

    private static PendingEvent event(int n) {
        return new PendingEvent(UUID.randomUUID(), "ConsultationPdfGenerated", 1, "trace-" + n,
                "{\"documentId\":\"d-" + n + "\"}", Instant.parse("2026-10-25T10:00:00Z").plusSeconds(n));
    }

    /** The store as the adapter behaves: a batch of the oldest rows and the outcome of each. */
    private static final class InMemoryPendingEvents implements PendingEvents {
        final List<PendingEvent> pending = new ArrayList<>();
        final Map<UUID, Outcome> outcomes = new LinkedHashMap<>();
        int lastLimit;

        @Override
        public void publishOldest(int limit, Publication publication) {
            lastLimit = limit;
            for (PendingEvent event : pending.subList(0, Math.min(limit, pending.size()))) {
                outcomes.put(event.eventId(), publication.publish(event));
            }
        }
    }

    private static final class RecordingPublisher implements EventPublisher {
        final List<PendingEvent> published = new ArrayList<>();
        PendingEvent failOn;

        @Override
        public void publish(PendingEvent event) {
            if (event.equals(failOn)) {
                throw new EventPublicationException("the broker did not confirm the message");
            }
            published.add(event);
        }
    }

    private final InMemoryPendingEvents events = new InMemoryPendingEvents();
    private final RecordingPublisher publisher = new RecordingPublisher();
    private final OutboxPublicationService service = new OutboxPublicationService(events, publisher, 2);

    @Test
    void publishesTheOldestBatchAndMarksEachEventPublished() {
        events.pending.addAll(List.of(event(1), event(2), event(3)));

        assertThat(service.publishPending()).isEqualTo(2);

        assertThat(events.lastLimit).isEqualTo(2);
        assertThat(publisher.published).containsExactly(events.pending.get(0), events.pending.get(1));
        assertThat(events.outcomes.values()).containsOnly(Outcome.published());
    }

    @Test
    void aFailureIsRecordedWithItsReasonAndTheRestOfTheBatchIsNotAttempted() {
        OutboxPublicationService larger = new OutboxPublicationService(events, publisher, 10);
        events.pending.addAll(List.of(event(1), event(2), event(3)));
        publisher.failOn = events.pending.get(1);

        assertThat(larger.publishPending()).isEqualTo(1);

        assertThat(events.outcomes.get(events.pending.get(0).eventId())).isEqualTo(Outcome.published());
        assertThat(events.outcomes.get(events.pending.get(1).eventId()))
                .isEqualTo(Outcome.failed("the broker did not confirm the message"));
        assertThat(events.outcomes.get(events.pending.get(2).eventId())).isEqualTo(Outcome.notAttempted());
        assertThat(publisher.published).containsExactly(events.pending.get(0));
    }

    @Test
    void nothingPendingPublishesNothing() {
        assertThat(service.publishPending()).isZero();
        assertThat(publisher.published).isEmpty();
    }

    @Test
    void theBatchSizeMustBePositive() {
        assertThatThrownBy(() -> new OutboxPublicationService(events, publisher, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
