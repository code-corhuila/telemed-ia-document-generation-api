package co.edu.corhuila.telemedia.documentgeneration.application.port.in;

import co.edu.corhuila.telemedia.documentgeneration.domain.model.BusinessRuleViolationException;

/**
 * The same Idempotency-Key arrived with a different body. It is a business rule
 * violation ({@code 422 BUSINESS_RULE_VIOLATION}), not a replay.
 */
public class IdempotencyKeyReusedException extends BusinessRuleViolationException {

    private static final long serialVersionUID = 1L;

    public IdempotencyKeyReusedException() {
        super("the Idempotency-Key was already used with a different request");
    }
}
