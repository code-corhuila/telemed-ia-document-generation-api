package co.edu.corhuila.telemedia.documentgeneration.application.usecase;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import co.edu.corhuila.telemedia.documentgeneration.application.port.out.CreationOutcome;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.IdempotentDocumentStore;
import co.edu.corhuila.telemedia.documentgeneration.domain.model.ConsultationDocument;

/** Fake of the output port with the same rules as the database (one document per summary). */
final class InMemoryIdempotentDocumentStore implements IdempotentDocumentStore {

    private record KeyEntry(UUID documentId, String requestHash) {
    }

    private final Map<String, KeyEntry> keys = new HashMap<>();
    private final Map<UUID, ConsultationDocument> documents = new HashMap<>();

    @Override
    public CreationOutcome createOnce(String idempotencyKey, String requestHash, ConsultationDocument candidate) {
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

    int documentCount() {
        return documents.size();
    }

    boolean hasKey(String idempotencyKey) {
        return keys.containsKey(idempotencyKey);
    }
}
