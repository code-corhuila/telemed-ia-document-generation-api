package co.edu.corhuila.telemedia.documentgeneration.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import co.edu.corhuila.telemedia.documentgeneration.application.port.out.CreationOutcome;
import co.edu.corhuila.telemedia.documentgeneration.domain.model.ConsultationDocument;
import co.edu.corhuila.telemedia.documentgeneration.domain.model.DocumentStatus;

/**
 * Two-phase idempotent creation (numeral 5.3.8, ddl-alter-002 of the -db) against the
 * real schema. Skipped without TEST_DATABASE_URL. Each test runs inside an outer
 * transaction that is rolled back; the store's own transaction becomes a savepoint,
 * so its commit and rollback behave as in production without leaving data behind.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class JdbcIdempotentDocumentStoreIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-10-21T20:00:00Z");

    private SingleConnectionDataSource dataSource;
    private DataSourceTransactionManager transactions;
    private TransactionStatus outer;
    private NamedParameterJdbcTemplate jdbc;
    private JdbcIdempotentDocumentStore store;
    private JdbcConsultationDocumentRepository reader;

    @BeforeEach
    void openOuterTransaction() {
        dataSource = new SingleConnectionDataSource(System.getenv("TEST_DATABASE_URL"), true);
        transactions = new DataSourceTransactionManager(dataSource);
        outer = transactions.getTransaction(new DefaultTransactionDefinition());
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        store = new JdbcIdempotentDocumentStore(jdbc, transactions);
        reader = new JdbcConsultationDocumentRepository(jdbc);
    }

    @AfterEach
    void rollBack() {
        transactions.rollback(outer);
        dataSource.destroy();
    }

    private static String key() {
        return "it-" + UUID.randomUUID();
    }

    private static ConsultationDocument candidate(UUID summary) {
        return ConsultationDocument.request(UUID.randomUUID(), summary, UUID.randomUUID(), T0);
    }

    private Map<String, Object> keyRow(String key) {
        return jdbc.queryForMap("""
                SELECT document_id, request_hash FROM document_generation.idempotency_key
                 WHERE idempotency_key = :key
                """, Map.of("key", key));
    }

    private long keyCount(String key) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM document_generation.idempotency_key WHERE idempotency_key = :key",
                Map.of("key", key), Long.class);
    }

    @Test
    void aNewKeyStoresThePendingDocumentAndBindsTheKeyToIt() {
        String key = key();
        ConsultationDocument candidate = candidate(UUID.randomUUID());

        CreationOutcome outcome = store.createOnce(key, "hash-1", candidate);

        assertThat(outcome).isInstanceOf(CreationOutcome.Created.class);
        assertThat(outcome.document().id()).isEqualTo(candidate.id());
        ConsultationDocument stored = reader.findById(candidate.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(DocumentStatus.PENDING);
        assertThat(stored.summaryId()).isEqualTo(candidate.summaryId());
        assertThat(stored.createdAt()).isEqualTo(T0);
        assertThat(keyRow(key))
                .containsEntry("document_id", candidate.id())
                .containsEntry("request_hash", "hash-1");
    }

    @Test
    void aReusedKeyReturnsItsDocumentAndStoredHashWithoutCreatingAnother() {
        String key = key();
        ConsultationDocument first = candidate(UUID.randomUUID());
        store.createOnce(key, "hash-1", first);

        CreationOutcome outcome = store.createOnce(key, "hash-2", candidate(UUID.randomUUID()));

        assertThat(outcome).isInstanceOf(CreationOutcome.KeyAlreadyUsed.class);
        assertThat(outcome.document().id()).isEqualTo(first.id());
        assertThat(((CreationOutcome.KeyAlreadyUsed) outcome).requestHash()).isEqualTo("hash-1");
    }

    @Test
    void aSummaryWithADocumentReturnsItAndDoesNotKeepTheNewKey() {
        UUID summary = UUID.randomUUID();
        ConsultationDocument first = candidate(summary);
        store.createOnce(key(), "hash-1", first);
        String otherKey = key();

        CreationOutcome outcome = store.createOnce(otherKey, "hash-1", candidate(summary));

        assertThat(outcome).isInstanceOf(CreationOutcome.SummaryAlreadyHasDocument.class);
        assertThat(outcome.document().id()).isEqualTo(first.id());
        assertThat(keyCount(otherKey)).isZero();
    }

    @Test
    void theCreatedDocumentIsTheOneReadBackFromTheDatabase() {
        ConsultationDocument candidate = ConsultationDocument.request(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), T0.plusNanos(123_456_400));

        CreationOutcome outcome = store.createOnce(key(), "hash-1", candidate);

        assertThat(outcome.document().createdAt()).isEqualTo(T0.plusNanos(123_456_000));
    }
}
