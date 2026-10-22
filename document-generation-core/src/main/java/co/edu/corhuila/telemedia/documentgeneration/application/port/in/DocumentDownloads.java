package co.edu.corhuila.telemedia.documentgeneration.application.port.in;

import java.util.UUID;

/** GET /{id}/download of document-service.yaml. */
public interface DocumentDownloads {

    /**
     * A presigned URL for the caller's own AVAILABLE document.
     *
     * @throws DocumentAccessDeniedException if the caller is not a patient or not its owner
     * @throws co.edu.corhuila.telemedia.documentgeneration.application.port.out.DocumentNotFoundException
     *         if it does not exist
     * @throws co.edu.corhuila.telemedia.documentgeneration.domain.model.BusinessRuleViolationException
     *         if the document is not AVAILABLE
     * @throws co.edu.corhuila.telemedia.documentgeneration.application.port.out.StorageUnavailableException
     *         if the URL cannot be issued
     */
    DownloadLink requestDownload(Caller caller, UUID documentId);
}
