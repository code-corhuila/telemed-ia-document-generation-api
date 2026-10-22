package co.edu.corhuila.telemedia.documentgeneration.adapter.in.http;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import co.edu.corhuila.telemedia.documentgeneration.domain.model.ConsultationDocument;

/**
 * Public representation {@code ConsultationDocument} of document-service.yaml. The domain
 * entity is never serialized directly (Anexo C); {@code storageReference} and the internal
 * {@code errorMessage} are never exposed (ADR-015). {@code generatedAt} only when AVAILABLE.
 *
 * <p>Dates are written as RFC 3339 UTC text ({@code 2026-10-21T15:00:00Z}) here, so the
 * contract does not depend on how the JSON mapper is configured.
 */
public record DocumentResponse(
        UUID id,
        UUID summaryId,
        UUID patientId,
        String format,
        String status,
        int retryCount,
        String createdAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) String generatedAt) {

    public static DocumentResponse from(ConsultationDocument document) {
        return new DocumentResponse(
                document.id(),
                document.summaryId(),
                document.patientId(),
                document.format().name(),
                document.status().name(),
                document.retryCount(),
                document.createdAt().toString(),
                document.generatedAt().map(Object::toString).orElse(null));
    }
}
