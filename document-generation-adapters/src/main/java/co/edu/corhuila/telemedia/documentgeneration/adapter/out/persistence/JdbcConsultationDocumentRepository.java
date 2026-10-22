package co.edu.corhuila.telemedia.documentgeneration.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import co.edu.corhuila.telemedia.documentgeneration.application.port.in.Page;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.PageRequest;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.ConsultationDocumentRepository;
import co.edu.corhuila.telemedia.documentgeneration.domain.model.ConsultationDocument;
import co.edu.corhuila.telemedia.documentgeneration.domain.model.DocumentStatus;

/**
 * Reads {@code document_generation.consultation_document} (schema owned by
 * telemed-ia-document-generation-db) as {@code document_generation_app}. Every row is
 * rebuilt through {@link ConsultationDocument#rehydrate}, so an inconsistent row never
 * reaches the domain. Filtering, ordering and paging happen in SQL: no unbounded reads.
 */
public final class JdbcConsultationDocumentRepository implements ConsultationDocumentRepository {

    private static final String SELECT = """
            SELECT document_id, summary_id, patient_id, status, retry_count,
                   storage_reference, error_message, created_at, generated_at
              FROM document_generation.consultation_document
            """;
    private static final String BY_PATIENT = " WHERE patient_id = :patientId";
    private static final String AND_STATUS = " AND status = :status";

    private static final RowMapper<ConsultationDocument> ROW = JdbcConsultationDocumentRepository::map;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcConsultationDocumentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<ConsultationDocument> findById(UUID id) {
        List<ConsultationDocument> rows = jdbc.query(SELECT + " WHERE document_id = :id",
                new MapSqlParameterSource("id", id), ROW);
        return rows.stream().findFirst();
    }

    /** The document of a summary, if any: the schema allows one per summary. */
    public Optional<ConsultationDocument> findBySummaryId(UUID summaryId) {
        List<ConsultationDocument> rows = jdbc.query(SELECT + " WHERE summary_id = :summaryId",
                new MapSqlParameterSource("summaryId", summaryId), ROW);
        return rows.stream().findFirst();
    }

    @Override
    public Page<ConsultationDocument> findByPatient(UUID patientId, Optional<DocumentStatus> status,
            PageRequest page) {
        MapSqlParameterSource params = new MapSqlParameterSource("patientId", patientId)
                .addValue("limit", page.limit())
                .addValue("offset", page.offset());
        String where = BY_PATIENT;
        if (status.isPresent()) {
            where += AND_STATUS;
            params.addValue("status", status.get().name());
        }
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM document_generation.consultation_document" + where, params, Long.class);
        List<ConsultationDocument> items = jdbc.query(
                SELECT + where + " ORDER BY created_at DESC, document_id DESC LIMIT :limit OFFSET :offset",
                params, ROW);
        return Page.of(items, page, total == null ? 0 : total);
    }

    private static ConsultationDocument map(ResultSet rs, int rowNumber) throws SQLException {
        return ConsultationDocument.rehydrate(
                rs.getObject("document_id", UUID.class),
                rs.getObject("summary_id", UUID.class),
                rs.getObject("patient_id", UUID.class),
                DocumentStatus.valueOf(rs.getString("status")),
                rs.getInt("retry_count"),
                rs.getString("storage_reference"),
                rs.getString("error_message"),
                instant(rs, "created_at"),
                instant(rs, "generated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
