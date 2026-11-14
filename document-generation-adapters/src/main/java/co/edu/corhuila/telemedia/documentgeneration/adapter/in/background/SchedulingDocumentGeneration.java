package co.edu.corhuila.telemedia.documentgeneration.adapter.in.background;

import java.util.Objects;
import java.util.function.Supplier;

import co.edu.corhuila.telemedia.documentgeneration.application.port.in.Caller;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DocumentGeneration;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.GenerateDocumentCommand;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.GenerationResult;
import co.edu.corhuila.telemedia.documentgeneration.domain.model.DocumentStatus;

/**
 * {@code POST /generate} followed by its background generation (DEC-006): once the PENDING
 * document is committed, it is queued with the request's correlation id. A repeated request
 * for a document still PENDING queues it again; the queue ignores duplicates.
 */
public final class SchedulingDocumentGeneration implements DocumentGeneration {

    private final DocumentGeneration delegate;
    private final PdfGenerationQueue queue;
    private final Supplier<String> correlationId;

    public SchedulingDocumentGeneration(DocumentGeneration delegate, PdfGenerationQueue queue,
            Supplier<String> correlationId) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.queue = Objects.requireNonNull(queue, "queue");
        this.correlationId = Objects.requireNonNull(correlationId, "correlationId");
    }

    @Override
    public GenerationResult request(Caller caller, GenerateDocumentCommand command) {
        GenerationResult result = delegate.request(caller, command);
        if (result.document().status() == DocumentStatus.PENDING) {
            queue.submit(result.document().id(), correlationId.get());
        }
        return result;
    }
}
