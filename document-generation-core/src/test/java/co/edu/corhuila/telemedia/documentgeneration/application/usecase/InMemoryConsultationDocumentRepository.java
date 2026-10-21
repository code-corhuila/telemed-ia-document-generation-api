package co.edu.corhuila.telemedia.documentgeneration.application.usecase;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import co.edu.corhuila.telemedia.documentgeneration.application.port.in.Page;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.PageRequest;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.ConsultationDocumentRepository;
import co.edu.corhuila.telemedia.documentgeneration.domain.model.ConsultationDocument;
import co.edu.corhuila.telemedia.documentgeneration.domain.model.DocumentStatus;

/** Fake of the output port: the core is tested without server or database (Anexo C). */
final class InMemoryConsultationDocumentRepository implements ConsultationDocumentRepository {

    private final Map<UUID, ConsultationDocument> documents = new LinkedHashMap<>();

    void save(ConsultationDocument document) {
        documents.put(document.id(), document);
    }

    @Override
    public Optional<ConsultationDocument> findById(UUID id) {
        return Optional.ofNullable(documents.get(id));
    }

    @Override
    public Page<ConsultationDocument> findByPatient(UUID patientId, Optional<DocumentStatus> status,
            PageRequest page) {
        List<ConsultationDocument> matching = documents.values().stream()
                .filter(document -> document.isOwnedBy(patientId))
                .filter(document -> status.map(document.status()::equals).orElse(true))
                .sorted(Comparator.comparing(ConsultationDocument::createdAt).reversed())
                .toList();
        List<ConsultationDocument> slice = matching.stream()
                .skip(page.offset())
                .limit(page.limit())
                .toList();
        return Page.of(slice, page, matching.size());
    }
}
