package co.edu.corhuila.telemedia.documentgeneration.domain.model;

/**
 * A domain invariant other than the status lifecycle is violated
 * (translated to {@code 422 BUSINESS_RULE_VIOLATION}).
 */
public class BusinessRuleViolationException extends DomainException {

    private static final long serialVersionUID = 1L;

    public BusinessRuleViolationException(String message) {
        super(message);
    }
}
