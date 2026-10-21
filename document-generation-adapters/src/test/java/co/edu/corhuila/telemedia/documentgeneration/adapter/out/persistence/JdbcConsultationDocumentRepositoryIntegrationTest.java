package co.edu.corhuila.telemedia.documentgeneration.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import co.edu.corhuila.telemedia.documentgeneration.application.port.in.Page;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.PageRequest;
import co.edu.corhuila.telemedia.documentgeneration.domain.model.ConsultationDocument;
import co.edu.corhuila.telemedia.documentgeneration.domain.model.DocumentStatus;

/**
 * Round trip against the real PostgreSQL schema of telemed-ia-document-generation-db
 * (Anexo C, "Integración"). Skipped, not failed, when TEST_DATABASE_URL is not set.
 *
 * <p>TEST_DATABASE_URL is a JDBC URL for the application user, for example
 * {@code jdbc:postgresql://localhost:5432/telemed_ia?user=document_generation_app&password=...}.
 * Every test runs in one transaction that is rolled back: nothing stays in the database.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class JdbcConsultationDocumentRepositoryIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-10-21T15:00:00Z");

    private SingleConnectionDataSource dataSource;
    private NamedParameterJdbcTemplate jdbc;
    private JdbcConsultationDocumentRepository repository;
    private UUID patient;

    @BeforeEach
    void openTransaction() {
        dataSource = new SingleConnectionDataSource(System.getenv("TEST_DATABASE_URL"), true);
        dataSource.setAutoCommit(false);
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        repository = new JdbcConsultationDocumentRepository(jdbc);
        patient = UUID.randomUUID();
    }

    @AfterEach
    void rollBack() throws SQLException {
        dataSource.getConnection().rollback();
        dataSource.destroy();
    }

    private UUID insert(UUID patientId, DocumentStatus status, Instant createdAt, int retries,
            String reference, String error, Instant generatedAt) {
        UUID id = UUID.randomUUID();
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("summary", UUID.randomUUID());
        row.put("patient", patientId);
        row.put("status", status.name());
        row.put("retries", retries);
        row.put("reference", reference);
        row.put("error", error);
        row.put("created", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
        row.put("generated", generatedAt == null ? null : OffsetDateTime.ofInstant(generatedAt, ZoneOffset.UTC));
        jdbc.update("""
                INSERT INTO document_generation.consultation_document
                    (document_id, summary_id, patient_id, format, status, retry_count,
                     storage_reference, error_message, created_at, generated_at)
                VALUES (:id, :summary, :patient, 'PDF', :status, :retries,
                        :reference, :error, :created, :generated)
                """, row);
        return id;
    }

    private UUID pending(UUID patientId, Instant createdAt) {
        return insert(patientId, DocumentStatus.PENDING, createdAt, 0, null, null, null);
    }

    @Test
    void anAvailableDocumentIsReadWithEveryField() {
        Instant generated = T0.plusSeconds(90);
        UUID id = insert(patient, DocumentStatus.AVAILABLE, T0, 2, "patients/p/doc.pdf", null, generated);

        ConsultationDocument document = repository.findById(id).orElseThrow();

        assertThat(document.id()).isEqualTo(id);
        assertThat(document.patientId()).isEqualTo(patient);
        assertThat(document.status()).isEqualTo(DocumentStatus.AVAILABLE);
        assertThat(document.retryCount()).isEqualTo(2);
        assertThat(document.storageReference()).contains("patients/p/doc.pdf");
        assertThat(document.errorMessage()).isEmpty();
        assertThat(document.createdAt()).isEqualTo(T0);
        assertThat(document.generatedAt()).contains(generated);
    }

    @Test
    void aFailedDocumentKeepsItsErrorMessage() {
        UUID id = insert(patient, DocumentStatus.ERROR, T0, 1, null, "PDF timeout (30s)", null);

        ConsultationDocument document = repository.findById(id).orElseThrow();

        assertThat(document.status()).isEqualTo(DocumentStatus.ERROR);
        assertThat(document.errorMessage()).contains("PDF timeout (30s)");
        assertThat(document.generatedAt()).isEmpty();
    }

    @Test
    void anUnknownIdIsEmpty() {
        assertThat(repository.findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void thePatientListIsMostRecentFirstAndExcludesOtherPatients() {
        UUID oldest = pending(patient, T0);
        UUID newest = pending(patient, T0.plusSeconds(120));
        UUID middle = pending(patient, T0.plusSeconds(60));
        pending(UUID.randomUUID(), T0.plusSeconds(300));

        Page<ConsultationDocument> page = repository.findByPatient(patient, Optional.empty(), new PageRequest(1, 20));

        assertThat(page.items()).extracting(ConsultationDocument::id).containsExactly(newest, middle, oldest);
        assertThat(page.total()).isEqualTo(3);
    }

    @Test
    void theStatusFilterAndThePageAreAppliedInTheDatabase() {
        pending(patient, T0);
        pending(patient, T0.plusSeconds(10));
        pending(patient, T0.plusSeconds(20));
        UUID failed = insert(patient, DocumentStatus.ERROR, T0.plusSeconds(30), 0, null, "bad summary", null);

        Page<ConsultationDocument> second = repository.findByPatient(patient,
                Optional.of(DocumentStatus.PENDING), new PageRequest(2, 2));
        Page<ConsultationDocument> errors = repository.findByPatient(patient,
                Optional.of(DocumentStatus.ERROR), new PageRequest(1, 20));

        assertThat(second.items()).hasSize(1);
        assertThat(second.total()).isEqualTo(3);
        assertThat(second.totalPages()).isEqualTo(2);
        assertThat(errors.items()).extracting(ConsultationDocument::id).containsExactly(failed);
    }

    @Test
    void timestampsKeepMicrosecondPrecision() {
        Instant precise = T0.plusNanos(123_456_000).truncatedTo(ChronoUnit.MICROS);
        UUID id = pending(patient, precise);

        assertThat(repository.findById(id).orElseThrow().createdAt()).isEqualTo(precise);
    }
}
