package co.edu.corhuila.telemedia.documentgeneration.adapter.out.persistence;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import co.edu.corhuila.telemedia.documentgeneration.application.port.out.CreationOutcome;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.IdempotentDocumentStore;
import co.edu.corhuila.telemedia.documentgeneration.domain.model.ConsultationDocument;

/**
 * Two-phase idempotent creation prepared by telemed-ia-document-generation-db
 * (ddl-alter-002), in one transaction:
 * <ol>
 *   <li>claim the key: {@code INSERT ... ON CONFLICT DO NOTHING RETURNING} with a NULL
 *       document; a concurrent request with the same key waits here for the first one;</li>
 *   <li>key already claimed: return its document and stored request hash;</li>
 *   <li>summary already has a document: roll back (the key is not kept) and return it;</li>
 *   <li>otherwise insert the PENDING document, bind the key to it and commit.</li>
 * </ol>
 * Two different keys for the same summary at the same time: the unique index on
 * {@code summary_id} rejects the second insert, which is rolled back and answered with
 * the document of the first one.
 */
public final class JdbcIdempotentDocumentStore implements IdempotentDocumentStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final JdbcConsultationDocumentRepository documents;

    public JdbcIdempotentDocumentStore(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transaction = new TransactionTemplate(Objects.requireNonNull(transactions, "transactions"));
        // A new transaction when none is active (production); a savepoint inside an
        // outer one, so its rollback stays local.
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        this.documents = new JdbcConsultationDocumentRepository(jdbc);
    }

    @Override
    public CreationOutcome createOnce(String idempotencyKey, String requestHash, ConsultationDocument candidate) {
        try {
            return transaction.execute(status -> {
                CreationOutcome outcome = attempt(idempotencyKey, requestHash, candidate);
                if (!(outcome instanceof CreationOutcome.Created)) {
                    status.setRollbackOnly();
                }
                return outcome;
            });
        } catch (DuplicateKeyException concurrentSummary) {
            return documents.findBySummaryId(candidate.summaryId())
                    .<CreationOutcome>map(CreationOutcome.SummaryAlreadyHasDocument::new)
                    .orElseThrow(() -> concurrentSummary);
        }
    }

    private CreationOutcome attempt(String key, String hash, ConsultationDocument candidate) {
        MapSqlParameterSource params = new MapSqlParameterSource("key", key).addValue("hash", hash);
        List<String> claimed = jdbc.queryForList("""
                INSERT INTO document_generation.idempotency_key (idempotency_key, document_id, request_hash)
                VALUES (:key, NULL, :hash)
                ON CONFLICT (idempotency_key) DO NOTHING
                RETURNING idempotency_key
                """, params, String.class);
        if (claimed.isEmpty()) {
            return keyAlreadyUsed(key);
        }
        var existing = documents.findBySummaryId(candidate.summaryId());
        if (existing.isPresent()) {
            return new CreationOutcome.SummaryAlreadyHasDocument(existing.get());
        }
        jdbc.update("""
                INSERT INTO document_generation.consultation_document
                    (document_id, summary_id, patient_id, format, status, retry_count, created_at)
                VALUES (:id, :summaryId, :patientId, 'PDF', 'PENDING', 0, :createdAt)
                """, new MapSqlParameterSource("id", candidate.id())
                .addValue("summaryId", candidate.summaryId())
                .addValue("patientId", candidate.patientId())
                .addValue("createdAt", OffsetDateTime.ofInstant(candidate.createdAt(), ZoneOffset.UTC)));
        jdbc.update("""
                UPDATE document_generation.idempotency_key SET document_id = :id
                 WHERE idempotency_key = :key
                """, new MapSqlParameterSource("id", candidate.id()).addValue("key", key));
        // Read back what the database stored (timestamps keep microseconds only).
        return new CreationOutcome.Created(documents.findById(candidate.id()).orElseThrow());
    }

    private CreationOutcome keyAlreadyUsed(String key) {
        var row = jdbc.queryForMap("""
                SELECT document_id, request_hash FROM document_generation.idempotency_key
                 WHERE idempotency_key = :key
                """, new MapSqlParameterSource("key", key));
        UUID documentId = (UUID) row.get("document_id");
        if (documentId == null) {
            throw new IllegalStateException("idempotency key without document: " + key);
        }
        ConsultationDocument document = documents.findById(documentId).orElseThrow();
        return new CreationOutcome.KeyAlreadyUsed(document, (String) row.get("request_hash"));
    }
}
