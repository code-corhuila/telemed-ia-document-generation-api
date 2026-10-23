package co.edu.corhuila.telemedia.documentgeneration.adapter.in.background;

import java.util.UUID;

/** Accepts documents whose PDF must be generated in the background (DEC-006). */
@FunctionalInterface
public interface PdfGenerationQueue {

    /**
     * @return {@code false} when the document was not queued (already queued or the queue is
     *         full); the resume sweep picks it up later
     */
    boolean submit(UUID documentId, String correlationId);
}
