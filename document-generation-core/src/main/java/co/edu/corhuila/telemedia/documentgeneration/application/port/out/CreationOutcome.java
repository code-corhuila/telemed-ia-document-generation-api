package co.edu.corhuila.telemedia.documentgeneration.application.port.out;

import co.edu.corhuila.telemedia.documentgeneration.domain.model.ConsultationDocument;

/** What happened when the store tried to create a document once. */
public sealed interface CreationOutcome {

    ConsultationDocument document();

    /** The key was new and the summary had no document: the candidate was stored. */
    record Created(ConsultationDocument document) implements CreationOutcome {
    }

    /** The key was already registered: the document it created and the hash of its request. */
    record KeyAlreadyUsed(ConsultationDocument document, String requestHash) implements CreationOutcome {
    }

    /** The summary already had a document (created with another key or by the event consumer). */
    record SummaryAlreadyHasDocument(ConsultationDocument document) implements CreationOutcome {
    }
}
