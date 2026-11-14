package co.edu.corhuila.telemedia.documentgeneration.adapter.out.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import co.edu.corhuila.telemedia.documentgeneration.application.port.out.PendingEvent;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.PendingEvents;

/**
 * The outbox for the relay (DEC-008). One transaction per batch: the rows are locked with
 * {@code FOR UPDATE SKIP LOCKED}, so another instance takes the next ones instead of
 * waiting, and each outcome is written before the commit.
 */
public final class JdbcPendingEvents implements PendingEvents {

    /** The CHECK of {@code last_error}. */
    private static final int MAX_ERROR = 2000;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcPendingEvents(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transaction = new TransactionTemplate(Objects.requireNonNull(transactions, "transactions"));
    }

    @Override
    public void publishOldest(int limit, Publication publication) {
        transaction.executeWithoutResult(status -> {
            for (PendingEvent event : lockOldest(limit)) {
                Outcome outcome = publication.publish(event);
                switch (outcome.status()) {
                    case PUBLISHED -> markPublished(event.eventId());
                    case FAILED -> markFailed(event.eventId(), outcome.error());
                    case NOT_ATTEMPTED -> {
                        // Left as it was, for the next round.
                    }
                }
            }
        });
    }

    private List<PendingEvent> lockOldest(int limit) {
        return jdbc.query("""
                SELECT event_id, event_type, event_version, correlation_id, payload::text AS payload, occurred_at
                FROM document_generation.outbox_event
                WHERE published_at IS NULL
                ORDER BY occurred_at, event_id
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
                """, Map.of("limit", limit), (row, n) -> new PendingEvent(
                row.getObject("event_id", UUID.class),
                row.getString("event_type"),
                row.getInt("event_version"),
                row.getString("correlation_id"),
                row.getString("payload"),
                row.getObject("occurred_at", OffsetDateTime.class).toInstant()));
    }

    /** Never before the event itself, whatever the clocks of the database and the service say. */
    private void markPublished(UUID eventId) {
        jdbc.update("""
                UPDATE document_generation.outbox_event
                SET published_at = GREATEST(now(), occurred_at)
                WHERE event_id = :eventId
                """, Map.of("eventId", eventId));
    }

    private void markFailed(UUID eventId, String error) {
        String reason = error == null || error.isBlank() ? "publication failed" : error;
        jdbc.update("""
                UPDATE document_generation.outbox_event
                SET publish_attempts = publish_attempts + 1, last_error = :error
                WHERE event_id = :eventId
                """, new MapSqlParameterSource("eventId", eventId)
                .addValue("error", reason.length() > MAX_ERROR ? reason.substring(0, MAX_ERROR) : reason));
    }
}
