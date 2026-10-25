package co.edu.corhuila.telemedia.documentgeneration.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import co.edu.corhuila.telemedia.documentgeneration.application.port.out.PendingEvent;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.PendingEvents.Outcome;

/**
 * The outbox read by the relay (DEC-008) against the real schema. Skipped without
 * TEST_DATABASE_URL. Each test runs inside an outer transaction that is rolled back; its
 * rows are dated in 2000, so they are the oldest of the table.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class JdbcPendingEventsIntegrationTest {

    private static final Instant OLD = Instant.parse("2000-01-01T00:00:00Z");

    private SingleConnectionDataSource dataSource;
    private DataSourceTransactionManager transactions;
    private TransactionStatus outer;
    private NamedParameterJdbcTemplate jdbc;
    private JdbcPendingEvents events;

    @BeforeEach
    void openOuterTransaction() {
        dataSource = new SingleConnectionDataSource(System.getenv("TEST_DATABASE_URL"), true);
        transactions = new DataSourceTransactionManager(dataSource);
        outer = transactions.getTransaction(new DefaultTransactionDefinition());
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        events = new JdbcPendingEvents(jdbc, transactions);
    }

    @AfterEach
    void rollBack() {
        transactions.rollback(outer);
        dataSource.destroy();
    }

    private UUID insert(Instant occurredAt, Instant publishedAt) {
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO document_generation.outbox_event
                    (event_id, aggregate_id, event_type, event_version, correlation_id, payload, occurred_at, published_at)
                VALUES (:eventId, :aggregateId, 'ConsultationPdfGenerated', 1, :correlationId,
                        CAST(:payload AS jsonb), :occurredAt, :publishedAt)
                """, new MapSqlParameterSource("eventId", eventId)
                .addValue("aggregateId", UUID.randomUUID())
                .addValue("correlationId", "trace-" + eventId)
                .addValue("payload", "{\"documentId\": \"" + eventId + "\"}")
                .addValue("occurredAt", OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC))
                .addValue("publishedAt", publishedAt == null ? null : OffsetDateTime.ofInstant(publishedAt, ZoneOffset.UTC)));
        return eventId;
    }

    private Map<String, Object> row(UUID eventId) {
        return jdbc.queryForMap("""
                SELECT published_at, publish_attempts, last_error FROM document_generation.outbox_event
                WHERE event_id = :eventId
                """, Map.of("eventId", eventId));
    }

    @Test
    void handsTheOldestUnpublishedRowsWithTheirEnvelopeFields() {
        UUID second = insert(OLD.plusSeconds(2), null);
        UUID first = insert(OLD.plusSeconds(1), null);
        insert(OLD, OLD);
        List<PendingEvent> seen = new ArrayList<>();

        events.publishOldest(2, event -> {
            seen.add(event);
            return Outcome.notAttempted();
        });

        assertThat(seen).extracting(PendingEvent::eventId).containsExactly(first, second);
        PendingEvent event = seen.get(0);
        assertThat(event.eventType()).isEqualTo("ConsultationPdfGenerated");
        assertThat(event.version()).isEqualTo(1);
        assertThat(event.correlationId()).isEqualTo("trace-" + first);
        assertThat(event.payload()).contains("\"documentId\"").contains(first.toString());
        assertThat(event.occurredAt()).isEqualTo(OLD.plusSeconds(1));
    }

    @Test
    void aPublishedEventGetsItsPublicationTimeAndIsNotHandedAgain() {
        UUID eventId = insert(OLD, null);

        events.publishOldest(1, event -> Outcome.published());

        assertThat(row(eventId).get("published_at")).isNotNull();
        List<UUID> again = new ArrayList<>();
        events.publishOldest(1, event -> {
            again.add(event.eventId());
            return Outcome.notAttempted();
        });
        assertThat(again).doesNotContain(eventId);
    }

    @Test
    void aFailureCountsTheAttemptAndKeepsTheReasonAndTheRowStaysPending() {
        UUID eventId = insert(OLD, null);

        events.publishOldest(1, event -> Outcome.failed("the broker did not confirm the message"));
        events.publishOldest(1, event -> Outcome.failed("connection refused"));

        Map<String, Object> row = row(eventId);
        assertThat(row.get("published_at")).isNull();
        assertThat(row.get("publish_attempts")).isEqualTo(2);
        assertThat(row.get("last_error")).isEqualTo("connection refused");
    }

    @Test
    void aRowThatWasNotAttemptedIsLeftAsItWas() {
        UUID eventId = insert(OLD, null);

        events.publishOldest(1, event -> Outcome.notAttempted());

        Map<String, Object> row = row(eventId);
        assertThat(row.get("published_at")).isNull();
        assertThat(row.get("publish_attempts")).isEqualTo(0);
        assertThat(row.get("last_error")).isNull();
    }
}
