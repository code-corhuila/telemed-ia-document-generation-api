package co.edu.corhuila.telemedia.documentgeneration.application.port.out;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code ConsultationPdfGenerated} (02-domain/domain-events.md): identifiers only, never
 * clinical content or the storage key. Written to the outbox with the AVAILABLE change.
 */
public record PdfGeneratedEvent(
        UUID eventId,
        UUID documentId,
        UUID summaryId,
        UUID patientId,
        Instant generatedAt,
        String correlationId) {

    public static final String TYPE = "ConsultationPdfGenerated";

    public PdfGeneratedEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(summaryId, "summaryId");
        Objects.requireNonNull(patientId, "patientId");
        Objects.requireNonNull(generatedAt, "generatedAt");
        Objects.requireNonNull(correlationId, "correlationId");
    }
}
