package co.edu.corhuila.telemedia.documentgeneration.application.port.in;

import java.util.List;
import java.util.UUID;

/** Background generation of the consultation PDF (DEC-001, DEC-005, DEC-006). */
public interface DocumentPdfGeneration {

    /** Runs one attempt for the document; safe to call twice for the same document. */
    GenerationAttempt generate(UUID documentId, String correlationId);

    /** Documents left PENDING, GENERATING or retryable ERROR, at most {@code limit}. */
    List<UUID> resumable(int limit);
}
