package co.edu.corhuila.telemedia.documentgeneration.application.port.out;

import java.util.List;
import java.util.UUID;

import co.edu.corhuila.telemedia.documentgeneration.domain.model.ConsultationDocument;

/**
 * Status changes of the generation. Each change applies only if the stored document is
 * still {@code from}, so two attempts on the same document never both proceed.
 */
public interface DocumentStatusStore {

    /** @return {@code false} if the document changed since {@code from} was read */
    boolean transition(ConsultationDocument from, ConsultationDocument to);

    /**
     * {@code GENERATING -> AVAILABLE} and the outbox row of {@code event} in the same
     * transaction (norma 5.3.11).
     *
     * @return {@code false} if the document changed since {@code from} was read
     */
    boolean complete(ConsultationDocument from, ConsultationDocument available, PdfGeneratedEvent event);

    /** Documents left PENDING, GENERATING or retryable ERROR, oldest first. */
    List<UUID> findResumable(int limit);
}
