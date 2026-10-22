package co.edu.corhuila.telemedia.documentgeneration.application.port.in;

import java.util.Objects;
import java.util.UUID;

/**
 * Request to generate the PDF of one post-consultation summary (POST /generate).
 * The HTTP adapter has already rejected a missing or malformed field with 400.
 */
public record GenerateDocumentCommand(String idempotencyKey, UUID summaryId, UUID patientId) {

    public static final int MIN_KEY_LENGTH = 8;
    public static final int MAX_KEY_LENGTH = 128;

    public GenerateDocumentCommand {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(summaryId, "summaryId");
        Objects.requireNonNull(patientId, "patientId");
        if (idempotencyKey.length() < MIN_KEY_LENGTH || idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("the idempotency key must have 8 to 128 characters");
        }
    }
}
