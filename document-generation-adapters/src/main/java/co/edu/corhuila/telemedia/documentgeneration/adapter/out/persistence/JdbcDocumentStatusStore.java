package co.edu.corhuila.telemedia.documentgeneration.adapter.out.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import co.edu.corhuila.telemedia.documentgeneration.application.port.out.DocumentStatusStore;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.PdfGeneratedEvent;
import co.edu.corhuila.telemedia.documentgeneration.domain.model.ConsultationDocument;

/**
 * Status changes of {@code document_generation.consultation_document} as
 * {@code document_generation_app}. Each UPDATE applies only while the row still has the
 * status and retry count that were read: every retry increments the count, so the pair
 * identifies the state and a stale attempt changes nothing. The database transition
 * trigger stays the last guard.
 *
 * <p>{@code GENERATING -> AVAILABLE} and the {@code ConsultationPdfGenerated} row of
 * {@code outbox_event} commit together or not at all (norma 5.3.11). Timestamps are kept
 * to the microsecond, as PostgreSQL stores them, so the column and the payload agree.
 */
public final class JdbcDocumentStatusStore implements DocumentStatusStore {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String UPDATE = """
            UPDATE document_generation.consultation_document
               SET status = :status, retry_count = :retryCount, storage_reference = :storageReference,
                   error_message = :errorMessage, generated_at = :generatedAt
             WHERE document_id = :id AND status = :fromStatus AND retry_count = :fromRetryCount
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcDocumentStatusStore(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transaction = new TransactionTemplate(Objects.requireNonNull(transactions, "transactions"));
        // A new transaction when none is active (production); a savepoint inside an
        // outer one, so its rollback stays local.
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    @Override
    public boolean transition(ConsultationDocument from, ConsultationDocument to) {
        return update(from, to);
    }

    @Override
    public boolean complete(ConsultationDocument from, ConsultationDocument available, PdfGeneratedEvent event) {
        Objects.requireNonNull(event, "event");
        return Boolean.TRUE.equals(transaction.execute(status -> {
            if (!update(from, available)) {
                return false;
            }
            Instant occurredAt = micros(event.generatedAt());
            jdbc.update("""
                    INSERT INTO document_generation.outbox_event
                        (event_id, aggregate_id, event_type, event_version, correlation_id, payload, occurred_at)
                    VALUES (:eventId, :documentId, :type, 1, :correlationId, CAST(:payload AS jsonb), :occurredAt)
                    """, new MapSqlParameterSource("eventId", event.eventId())
                    .addValue("documentId", event.documentId())
                    .addValue("type", PdfGeneratedEvent.TYPE)
                    .addValue("correlationId", event.correlationId())
                    .addValue("payload", payload(event, occurredAt))
                    .addValue("occurredAt", timestamp(occurredAt)));
            return true;
        }));
    }

    @Override
    public List<UUID> findResumable(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return jdbc.queryForList("""
                SELECT document_id FROM document_generation.consultation_document
                 WHERE status IN ('PENDING', 'GENERATING')
                    OR (status = 'ERROR' AND retry_count < :maxRetries)
                 ORDER BY created_at, document_id
                 LIMIT :limit
                """, new MapSqlParameterSource("maxRetries", ConsultationDocument.MAX_RETRIES)
                .addValue("limit", limit), UUID.class);
    }

    private boolean update(ConsultationDocument from, ConsultationDocument to) {
        if (!from.id().equals(to.id())) {
            throw new IllegalArgumentException("from and to must be the same document");
        }
        return jdbc.update(UPDATE, new MapSqlParameterSource("id", from.id())
                .addValue("fromStatus", from.status().name())
                .addValue("fromRetryCount", from.retryCount())
                .addValue("status", to.status().name())
                .addValue("retryCount", to.retryCount())
                .addValue("storageReference", to.storageReference().orElse(null))
                .addValue("errorMessage", to.errorMessage().orElse(null))
                .addValue("generatedAt", to.generatedAt().map(at -> timestamp(micros(at))).orElse(null)))
                == 1;
    }

    /** {@code 02-domain/domain-events.md}: identifiers only, never clinical content or the key. */
    private static String payload(PdfGeneratedEvent event, Instant generatedAt) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("documentId", event.documentId().toString());
        payload.put("summaryId", event.summaryId().toString());
        payload.put("appointmentId", event.appointmentId().toString());
        payload.put("patientId", event.patientId().toString());
        payload.put("generatedAt", generatedAt.toString());
        try {
            return JSON.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("the event payload could not be written", e);
        }
    }

    private static Instant micros(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
