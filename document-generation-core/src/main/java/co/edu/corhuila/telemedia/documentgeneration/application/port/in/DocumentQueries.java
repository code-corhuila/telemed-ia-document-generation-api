package co.edu.corhuila.telemedia.documentgeneration.application.port.in;

import java.util.Optional;
import java.util.UUID;

import co.edu.corhuila.telemedia.documentgeneration.domain.model.ConsultationDocument;
import co.edu.corhuila.telemedia.documentgeneration.domain.model.DocumentStatus;

/** Read operations the service offers (GET / and GET /{id} of document-service.yaml). */
public interface DocumentQueries {

    /**
     * The caller's own documents, most recent first.
     *
     * @throws DocumentAccessDeniedException if the caller is not a patient
     */
    Page<ConsultationDocument> listMine(Caller caller, Optional<DocumentStatus> status, PageRequest page);

    /**
     * One document of the caller.
     *
     * @throws DocumentAccessDeniedException if the caller is not a patient or not its owner
     * @throws co.edu.corhuila.telemedia.documentgeneration.application.port.out.DocumentNotFoundException
     *         if it does not exist
     */
    ConsultationDocument getById(Caller caller, UUID documentId);
}
