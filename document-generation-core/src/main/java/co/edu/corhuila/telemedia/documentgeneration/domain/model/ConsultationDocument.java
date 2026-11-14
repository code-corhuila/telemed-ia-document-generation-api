package co.edu.corhuila.telemedia.documentgeneration.domain.model;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Aggregate root: the PDF generated for one post-consultation summary.
 *
 * <p>Immutable: every transition returns a new instance. The invariants mirror the
 * constraints of {@code document_generation.consultation_document}, so an invalid
 * state is rejected here before the database has to reject it.
 */
public final class ConsultationDocument {

    /** DEC-005: generation is retried at most three times. */
    public static final int MAX_RETRIES = 3;
    public static final int MAX_STORAGE_REFERENCE_LENGTH = 512;
    public static final int MAX_ERROR_MESSAGE_LENGTH = 2000;

    private final UUID id;
    private final UUID summaryId;
    private final UUID patientId;
    private final DocumentStatus status;
    private final int retryCount;
    private final String storageReference;
    private final String errorMessage;
    private final Instant createdAt;
    private final Instant generatedAt;

    private ConsultationDocument(UUID id, UUID summaryId, UUID patientId, DocumentStatus status,
            int retryCount, String storageReference, String errorMessage,
            Instant createdAt, Instant generatedAt) {
        this.id = required(id, "id");
        this.summaryId = required(summaryId, "summaryId");
        this.patientId = required(patientId, "patientId");
        this.status = required(status, "status");
        this.createdAt = required(createdAt, "createdAt");
        if (retryCount < 0 || retryCount > MAX_RETRIES) {
            throw new BusinessRuleViolationException("retryCount must be between 0 and " + MAX_RETRIES);
        }
        this.retryCount = retryCount;
        this.storageReference = onlyWhen(status == DocumentStatus.AVAILABLE, storageReference,
                "storageReference", MAX_STORAGE_REFERENCE_LENGTH);
        this.generatedAt = onlyWhen(status == DocumentStatus.AVAILABLE, generatedAt, "generatedAt");
        this.errorMessage = onlyWhen(status == DocumentStatus.ERROR, errorMessage,
                "errorMessage", MAX_ERROR_MESSAGE_LENGTH);
    }

    /** A new document for a summary: {@code PENDING}, no attempts and no result yet. */
    public static ConsultationDocument request(UUID id, UUID summaryId, UUID patientId, Instant createdAt) {
        return new ConsultationDocument(id, summaryId, patientId, DocumentStatus.PENDING, 0,
                null, null, createdAt, null);
    }

    /** Restores a stored document, validating every invariant. */
    public static ConsultationDocument rehydrate(UUID id, UUID summaryId, UUID patientId,
            DocumentStatus status, int retryCount, String storageReference, String errorMessage,
            Instant createdAt, Instant generatedAt) {
        return new ConsultationDocument(id, summaryId, patientId, status, retryCount,
                storageReference, errorMessage, createdAt, generatedAt);
    }

    /** {@code PENDING -> GENERATING}, or a retry {@code ERROR -> GENERATING} that counts one attempt. */
    public ConsultationDocument startGeneration() {
        requireTransitionTo(DocumentStatus.GENERATING);
        int attempts = retryCount;
        if (status == DocumentStatus.ERROR) {
            if (!canRetry()) {
                throw new RetryLimitExceededException(id);
            }
            attempts++;
        }
        return next(DocumentStatus.GENERATING, attempts, null, null, null);
    }

    /** {@code GENERATING -> AVAILABLE}, with the object key of the stored PDF. */
    public ConsultationDocument markAvailable(String storageReference, Instant generatedAt) {
        requireTransitionTo(DocumentStatus.AVAILABLE);
        return next(DocumentStatus.AVAILABLE, retryCount, storageReference, null, generatedAt);
    }

    /** {@code PENDING|GENERATING -> ERROR}. A failure never invalidates the consultation (ADR-010). */
    public ConsultationDocument markFailed(String errorMessage) {
        requireTransitionTo(DocumentStatus.ERROR);
        return next(DocumentStatus.ERROR, retryCount, null, errorMessage, null);
    }

    public boolean canRetry() {
        return status == DocumentStatus.ERROR && retryCount < MAX_RETRIES;
    }

    /** DEC-004: a patient can only access documents of their own consultations. */
    public boolean isOwnedBy(UUID candidatePatientId) {
        return patientId.equals(candidatePatientId);
    }

    public UUID id() { return id; }
    public UUID summaryId() { return summaryId; }
    public UUID patientId() { return patientId; }
    public DocumentFormat format() { return DocumentFormat.PDF; }
    public DocumentStatus status() { return status; }
    public int retryCount() { return retryCount; }
    public Optional<String> storageReference() { return Optional.ofNullable(storageReference); }
    public Optional<String> errorMessage() { return Optional.ofNullable(errorMessage); }
    public Instant createdAt() { return createdAt; }
    public Optional<Instant> generatedAt() { return Optional.ofNullable(generatedAt); }

    @Override
    public boolean equals(Object other) {
        return other instanceof ConsultationDocument document && id.equals(document.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    private void requireTransitionTo(DocumentStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidStatusTransitionException(status, target);
        }
    }

    private ConsultationDocument next(DocumentStatus nextStatus, int attempts, String reference,
            String message, Instant generated) {
        return new ConsultationDocument(id, summaryId, patientId, nextStatus, attempts,
                reference, message, createdAt, generated);
    }

    private static <T> T required(T value, String field) {
        if (value == null) {
            throw new BusinessRuleViolationException(field + " is required");
        }
        return value;
    }

    private static Instant onlyWhen(boolean expected, Instant value, String field) {
        if (expected && value == null) {
            throw new BusinessRuleViolationException(field + " is required in this status");
        }
        if (!expected && value != null) {
            throw new BusinessRuleViolationException(field + " is not allowed in this status");
        }
        return value;
    }

    private static String onlyWhen(boolean expected, String value, String field, int maxLength) {
        if (!expected) {
            if (value != null) {
                throw new BusinessRuleViolationException(field + " is not allowed in this status");
            }
            return null;
        }
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new BusinessRuleViolationException(field + " must have 1 to " + maxLength + " characters");
        }
        return value;
    }
}
