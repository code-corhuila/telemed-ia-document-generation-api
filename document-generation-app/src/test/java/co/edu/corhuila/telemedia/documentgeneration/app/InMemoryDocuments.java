package co.edu.corhuila.telemedia.documentgeneration.app;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import co.edu.corhuila.telemedia.documentgeneration.application.port.in.Page;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.PageRequest;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.ConsultationDocumentRepository;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.CreationOutcome;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.IdempotentDocumentStore;
import co.edu.corhuila.telemedia.documentgeneration.domain.model.ConsultationDocument;
import co.edu.corhuila.telemedia.documentgeneration.domain.model.DocumentStatus;

/**
 * Anexo C, HTTP level: the running server with an in-memory repository, so the contract checks
 * run anywhere, CI included. It keeps the rules of the database: one document per summary, and
 * an idempotency key bound to its first request. The SQL itself is covered by the JDBC
 * integration tests against PostgreSQL.
 */
final class InMemoryDocuments implements ConsultationDocumentRepository, IdempotentDocumentStore {

    private record KeyEntry(UUID documentId, String requestHash) {
    }

    private final Map<String, KeyEntry> keys = new HashMap<>();
    private final Map<UUID, ConsultationDocument> documents = new LinkedHashMap<>();

    @Override
    public synchronized CreationOutcome createOnce(String idempotencyKey, String requestHash,
            ConsultationDocument candidate) {
        KeyEntry entry = keys.get(idempotencyKey);
        if (entry != null) {
            return new CreationOutcome.KeyAlreadyUsed(documents.get(entry.documentId()), entry.requestHash());
        }
        Optional<ConsultationDocument> existing = documents.values().stream()
                .filter(document -> document.summaryId().equals(candidate.summaryId()))
                .findFirst();
        if (existing.isPresent()) {
            return new CreationOutcome.SummaryAlreadyHasDocument(existing.get());
        }
        documents.put(candidate.id(), candidate);
        keys.put(idempotencyKey, new KeyEntry(candidate.id(), requestHash));
        return new CreationOutcome.Created(candidate);
    }

    @Override
    public synchronized Optional<ConsultationDocument> findById(UUID id) {
        return Optional.ofNullable(documents.get(id));
    }

    @Override
    public synchronized Page<ConsultationDocument> findByPatient(UUID patientId, Optional<DocumentStatus> status,
            PageRequest page) {
        List<ConsultationDocument> matching = documents.values().stream()
                .filter(document -> document.isOwnedBy(patientId))
                .filter(document -> status.map(document.status()::equals).orElse(true))
                .sorted(Comparator.comparing(ConsultationDocument::createdAt).reversed())
                .toList();
        return Page.of(matching.stream().skip(page.offset()).limit(page.limit()).toList(), page, matching.size());
    }

    /** Takes a document through its generation, as the background attempt does. */
    synchronized void markAvailable(UUID id) {
        documents.computeIfPresent(id, (key, document) -> document.startGeneration()
                .markAvailable("contract/" + id + ".pdf", Instant.now()));
    }
}
