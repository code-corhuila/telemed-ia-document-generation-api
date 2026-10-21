package co.edu.corhuila.telemedia.documentgeneration.application.port.out;

import java.util.Optional;
import java.util.UUID;

import co.edu.corhuila.telemedia.documentgeneration.application.port.in.Page;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.PageRequest;
import co.edu.corhuila.telemedia.documentgeneration.domain.model.ConsultationDocument;
import co.edu.corhuila.telemedia.documentgeneration.domain.model.DocumentStatus;

/** What the service needs from storage of {@code document_generation.consultation_document}. */
public interface ConsultationDocumentRepository {

    Optional<ConsultationDocument> findById(UUID id);

    /** Documents of one patient, optionally of one status, ordered by {@code createdAt} descending. */
    Page<ConsultationDocument> findByPatient(UUID patientId, Optional<DocumentStatus> status, PageRequest page);
}
