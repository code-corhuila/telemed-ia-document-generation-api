package co.edu.corhuila.telemedia.documentgeneration.application.usecase;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import co.edu.corhuila.telemedia.documentgeneration.application.port.in.Caller;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DocumentAccessDeniedException;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DocumentQueries;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.Page;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.PageRequest;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.ConsultationDocumentRepository;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.DocumentNotFoundException;
import co.edu.corhuila.telemedia.documentgeneration.domain.model.ConsultationDocument;
import co.edu.corhuila.telemedia.documentgeneration.domain.model.DocumentStatus;

/**
 * Read use cases with the DEC-004 rules: the caller must be a patient and may only
 * see their own documents. The patient comes from the token, never from a parameter.
 */
public final class DocumentQueryService implements DocumentQueries {

    private final ConsultationDocumentRepository repository;

    public DocumentQueryService(ConsultationDocumentRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public Page<ConsultationDocument> listMine(Caller caller, Optional<DocumentStatus> status, PageRequest page) {
        UUID patientId = requirePatient(caller);
        return repository.findByPatient(patientId, Objects.requireNonNull(status, "status"),
                Objects.requireNonNull(page, "page"));
    }

    @Override
    public ConsultationDocument getById(Caller caller, UUID documentId) {
        UUID patientId = requirePatient(caller);
        ConsultationDocument document = repository.findById(Objects.requireNonNull(documentId, "documentId"))
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        if (!document.isOwnedBy(patientId)) {
            throw new DocumentAccessDeniedException();
        }
        return document;
    }

    private static UUID requirePatient(Caller caller) {
        return Objects.requireNonNull(caller, "caller").patientId()
                .orElseThrow(DocumentAccessDeniedException::new);
    }
}
