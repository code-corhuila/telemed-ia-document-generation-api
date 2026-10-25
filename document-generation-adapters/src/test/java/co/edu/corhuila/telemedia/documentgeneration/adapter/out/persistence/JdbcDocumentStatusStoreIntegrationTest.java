package co.edu.corhuila.telemedia.documentgeneration.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import co.edu.corhuila.telemedia.documentgeneration.application.port.out.PdfGeneratedEvent;
import co.edu.corhuila.telemedia.documentgeneration.domain.model.ConsultationDocument;
import co.edu.corhuila.telemedia.documentgeneration.domain.model.DocumentStatus;

/**
 * Status changes and the outbox row (norma 5.3.11, DEC-005, DEC-006) against the real
 * schema, its CHECK constraints and its transition trigger. Skipped without
 * TEST_DATABASE_URL. Each test runs inside an outer transaction that is rolled back.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class JdbcDocumentStatusStoreIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-10-23T14:00:00Z");
    private static final String KEY = "patients/p/consultations/s/d.pdf";

    private SingleConnectionDataSource dataSource;
    private DataSourceTransactionManager transactions;
    private TransactionStatus outer;
    private NamedParameterJdbcTemplate jdbc;
    private JdbcDocumentStatusStore store;
    private JdbcConsultationDocumentRepository reader;

    @BeforeEach
    void openOuterTransaction() {
        dataSource = new SingleConnectionDataSource(System.getenv("TEST_DATABASE_URL"), true);
        transactions = new DataSourceTransactionManager(dataSource);
        outer = transactions.getTransaction(new DefaultTransactionDefinition());
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        store = new JdbcDocumentStatusStore(jdbc, transactions);
        reader = new JdbcConsultationDocumentRepository(jdbc);
    }

    @AfterEach
    void rollBack() {
        transactions.rollback(outer);
        dataSource.destroy();
    }

    private ConsultationDocument pending(Instant createdAt) {
        return ConsultationDocument.request(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), createdAt);
    }

    /** ERROR after the first attempt and {@code retries} more. */
    private ConsultationDocument failed(int retries) {
        ConsultationDocument document = pending(T0).startGeneration().markFailed("PDF error");
        for (int i = 0; i < retries; i++) {
            document = document.startGeneration().markFailed("PDF error");
        }
        return document;
    }

    /** Inserts the row as it is: the transition trigger only guards updates. */
    private ConsultationDocument insert(ConsultationDocument document) {
        jdbc.update("""
                INSERT INTO document_generation.consultation_document
                    (document_id, summary_id, patient_id, format, status, retry_count,
                     storage_reference, error_message, created_at, generated_at)
                VALUES (:id, :summaryId, :patientId, 'PDF', :status, :retryCount,
                        :storageReference, :errorMessage, :createdAt, :generatedAt)
                """, new MapSqlParameterSource("id", document.id())
                .addValue("summaryId", document.summaryId())
                .addValue("patientId", document.patientId())
                .addValue("status", document.status().name())
                .addValue("retryCount", document.retryCount())
                .addValue("storageReference", document.storageReference().orElse(null))
                .addValue("errorMessage", document.errorMessage().orElse(null))
                .addValue("createdAt", OffsetDateTime.ofInstant(document.createdAt(), ZoneOffset.UTC))
                .addValue("generatedAt", document.generatedAt()
                        .map(at -> OffsetDateTime.ofInstant(at, ZoneOffset.UTC)).orElse(null)));
        return document;
    }

    private ConsultationDocument stored(ConsultationDocument document) {
        return reader.findById(document.id()).orElseThrow();
    }

    private static final UUID APPOINTMENT = UUID.fromString("a0000000-0000-0000-0000-000000000123");

    private PdfGeneratedEvent event(ConsultationDocument document, Instant generatedAt, String correlationId) {
        return new PdfGeneratedEvent(UUID.randomUUID(), document.id(), document.summaryId(), APPOINTMENT,
                document.patientId(), generatedAt, correlationId);
    }

    private List<Map<String, Object>> outbox(ConsultationDocument document) {
        return jdbc.queryForList("""
                SELECT event_id, aggregate_id, event_type, event_version, correlation_id, payload::text AS payload,
                       occurred_at, published_at, publish_attempts, last_error
                  FROM document_generation.outbox_event
                 WHERE aggregate_id = :id
                """, new MapSqlParameterSource("id", document.id()));
    }

    @Test
    void pendingStartsGeneratingWithoutCountingARetry() {
        ConsultationDocument pending = insert(pending(T0));

        assertThat(store.transition(pending, pending.startGeneration())).isTrue();

        assertThat(stored(pending).status()).isEqualTo(DocumentStatus.GENERATING);
        assertThat(stored(pending).retryCount()).isZero();
    }

    @Test
    void aRetryStartsGeneratingCountsTheAttemptAndClearsTheError() {
        ConsultationDocument failed = insert(failed(1));

        assertThat(store.transition(failed, failed.startGeneration())).isTrue();

        ConsultationDocument stored = stored(failed);
        assertThat(stored.status()).isEqualTo(DocumentStatus.GENERATING);
        assertThat(stored.retryCount()).isEqualTo(2);
        assertThat(stored.errorMessage()).isEmpty();
    }

    @Test
    void aFailedAttemptStoresItsNeutralMessage() {
        ConsultationDocument generating = insert(pending(T0).startGeneration());

        assertThat(store.transition(generating, generating.markFailed("storage unavailable"))).isTrue();

        assertThat(stored(generating).status()).isEqualTo(DocumentStatus.ERROR);
        assertThat(stored(generating).errorMessage()).contains("storage unavailable");
    }

    @Test
    void aChangeFromAStaleReadIsNotApplied() {
        ConsultationDocument pending = insert(pending(T0));
        store.transition(pending, pending.startGeneration());

        assertThat(store.transition(pending, pending.startGeneration())).isFalse();
    }

    @Test
    void theSameStatusWithAnotherRetryCountIsAStaleRead() {
        ConsultationDocument readFirst = failed(0);
        insert(readFirst.startGeneration().markFailed("PDF error"));

        assertThat(store.transition(readFirst, readFirst.startGeneration())).isFalse();
        assertThat(stored(readFirst).retryCount()).isEqualTo(1);
    }

    @Test
    void completingStoresTheDocumentAndItsOutboxEventTogether() {
        ConsultationDocument generating = insert(pending(T0).startGeneration());
        Instant generatedAt = T0.plusSeconds(5);
        PdfGeneratedEvent event = event(generating, generatedAt, "corr-1");

        assertThat(store.complete(generating, generating.markAvailable(KEY, generatedAt), event)).isTrue();

        ConsultationDocument stored = stored(generating);
        assertThat(stored.status()).isEqualTo(DocumentStatus.AVAILABLE);
        assertThat(stored.storageReference()).contains(KEY);
        assertThat(stored.generatedAt()).contains(generatedAt);
        assertThat(outbox(generating)).singleElement().satisfies(row -> {
            assertThat(row)
                    .containsEntry("event_id", event.eventId())
                    .containsEntry("event_type", PdfGeneratedEvent.TYPE)
                    .containsEntry("event_version", 1)
                    .containsEntry("correlation_id", "corr-1")
                    .containsEntry("publish_attempts", 0)
                    .containsEntry("published_at", null)
                    .containsEntry("last_error", null);
            assertThat(((Timestamp) row.get("occurred_at")).toInstant()).isEqualTo(generatedAt);
        });
    }

    @Test
    void theEventPayloadCarriesIdentifiersOnly() throws Exception {
        ConsultationDocument generating = insert(pending(T0).startGeneration());
        Instant generatedAt = T0.plusNanos(123_456_789);

        store.complete(generating, generating.markAvailable(KEY, generatedAt), event(generating, generatedAt, "c"));

        JsonNode payload = new ObjectMapper().readTree((String) outbox(generating).get(0).get("payload"));
        assertThat(payload.properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("documentId", "summaryId", "appointmentId", "patientId", "generatedAt");
        assertThat(payload.get("appointmentId").asText()).isEqualTo(APPOINTMENT.toString());
        assertThat(payload.get("documentId").asText()).isEqualTo(generating.id().toString());
        assertThat(payload.get("summaryId").asText()).isEqualTo(generating.summaryId().toString());
        assertThat(payload.get("patientId").asText()).isEqualTo(generating.patientId().toString());
        // The same instant the column keeps (microseconds).
        assertThat(payload.get("generatedAt").asText()).isEqualTo("2026-10-23T14:00:00.123456Z");
        assertThat(payload.toString()).doesNotContain(KEY);
    }

    @Test
    void completingAStaleReadWritesNoEvent() {
        ConsultationDocument generating = insert(pending(T0).startGeneration());
        ConsultationDocument available = generating.markAvailable(KEY, T0);
        store.complete(generating, available, event(generating, T0, "c"));

        assertThat(store.complete(generating, available, event(generating, T0, "c"))).isFalse();

        assertThat(outbox(generating)).hasSize(1);
    }

    @Test
    void anEventTheOutboxRejectsLeavesTheDocumentGenerating() {
        ConsultationDocument generating = insert(pending(T0).startGeneration());
        PdfGeneratedEvent tooLongCorrelation = event(generating, T0, "c".repeat(129));

        assertThatThrownBy(() -> store.complete(generating, generating.markAvailable(KEY, T0), tooLongCorrelation))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(stored(generating).status()).isEqualTo(DocumentStatus.GENERATING);
        assertThat(outbox(generating)).isEmpty();
    }

    @Test
    void resumableDocumentsArePendingGeneratingOrRetryableErrorOldestFirst() {
        ConsultationDocument oldest = insert(pending(Instant.parse("1990-01-01T00:00:00Z")));
        ConsultationDocument generating = insert(pending(Instant.parse("1990-01-02T00:00:00Z")).startGeneration());
        ConsultationDocument retryable = insert(failed(1));
        ConsultationDocument exhausted = insert(failed(ConsultationDocument.MAX_RETRIES));
        ConsultationDocument available = insert(pending(T0).startGeneration().markAvailable(KEY, T0));

        List<UUID> resumable = store.findResumable(10_000);

        assertThat(resumable).contains(oldest.id(), generating.id(), retryable.id())
                .doesNotContain(exhausted.id(), available.id());
        assertThat(resumable.indexOf(oldest.id())).isLessThan(resumable.indexOf(generating.id()));
        assertThat(store.findResumable(1)).containsExactly(oldest.id());
    }

    @Test
    void theResumableLimitMustBePositive() {
        assertThatThrownBy(() -> store.findResumable(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
