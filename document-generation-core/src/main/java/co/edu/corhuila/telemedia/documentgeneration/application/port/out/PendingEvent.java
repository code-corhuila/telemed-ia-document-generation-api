package co.edu.corhuila.telemedia.documentgeneration.application.port.out;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An outbox row not published yet, with the ADR-011 envelope fields. {@code payload} is the
 * JSON object stored with the event, kept as text so it is published exactly as written.
 */
public record PendingEvent(UUID eventId, String eventType, int version, String correlationId, String payload,
        Instant occurredAt) {

    public PendingEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
