package co.edu.corhuila.telemedia.documentgeneration.domain.model;

import java.util.UUID;

/**
 * DEC-005: PDF generation is retried at most {@value ConsultationDocument#MAX_RETRIES} times.
 */
public final class RetryLimitExceededException extends BusinessRuleViolationException {

    private static final long serialVersionUID = 1L;

    public RetryLimitExceededException(UUID documentId) {
        super("Consultation document " + documentId + " reached the retry limit of "
                + ConsultationDocument.MAX_RETRIES);
    }
}
