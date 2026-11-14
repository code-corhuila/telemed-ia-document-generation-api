package co.edu.corhuila.telemedia.documentgeneration.application.port.out;

import co.edu.corhuila.telemedia.documentgeneration.domain.model.ConsultationDocument;

/**
 * Creates a document and registers its Idempotency-Key atomically (numeral 5.3.8):
 * either both are stored, or neither is and the existing state is reported.
 */
public interface IdempotentDocumentStore {

    CreationOutcome createOnce(String idempotencyKey, String requestHash, ConsultationDocument candidate);
}
