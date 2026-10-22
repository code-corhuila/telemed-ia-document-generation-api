package co.edu.corhuila.telemedia.documentgeneration.application.usecase;

import java.util.Objects;
import java.util.UUID;

import co.edu.corhuila.telemedia.documentgeneration.application.port.in.Caller;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DocumentDownloads;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DocumentQueries;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DownloadLink;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.DocumentStorage;
import co.edu.corhuila.telemedia.documentgeneration.domain.model.BusinessRuleViolationException;
import co.edu.corhuila.telemedia.documentgeneration.domain.model.ConsultationDocument;
import co.edu.corhuila.telemedia.documentgeneration.domain.model.DocumentStatus;

/**
 * GET /{id}/download rules (ADR-015): the DEC-004 read rules apply first (reused from
 * {@link DocumentQueries}), so no URL is issued for someone else's document; only an
 * AVAILABLE document has an object in storage.
 */
public final class DocumentDownloadService implements DocumentDownloads {

    private final DocumentQueries queries;
    private final DocumentStorage storage;

    public DocumentDownloadService(DocumentQueries queries, DocumentStorage storage) {
        this.queries = Objects.requireNonNull(queries, "queries");
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    @Override
    public DownloadLink requestDownload(Caller caller, UUID documentId) {
        ConsultationDocument document = queries.getById(caller, documentId);
        if (document.status() != DocumentStatus.AVAILABLE) {
            throw new BusinessRuleViolationException("the document is not available for download");
        }
        return storage.presignDownload(document.storageReference().orElseThrow());
    }
}
