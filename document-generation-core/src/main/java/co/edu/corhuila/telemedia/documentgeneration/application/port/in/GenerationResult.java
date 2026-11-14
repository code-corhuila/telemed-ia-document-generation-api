package co.edu.corhuila.telemedia.documentgeneration.application.port.in;

import java.util.Objects;

import co.edu.corhuila.telemedia.documentgeneration.domain.model.ConsultationDocument;

/**
 * The document for the request and whether this call created it
 * ({@code 201}) or returned an existing one ({@code 200}).
 */
public record GenerationResult(ConsultationDocument document, boolean created) {

    public GenerationResult {
        Objects.requireNonNull(document, "document");
    }
}
