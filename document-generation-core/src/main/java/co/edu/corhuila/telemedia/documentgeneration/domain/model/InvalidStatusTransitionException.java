package co.edu.corhuila.telemedia.documentgeneration.domain.model;

/**
 * The lifecycle forbids moving from one status to another
 * (translated to {@code 422 INVALID_STATUS_TRANSITION}).
 */
public final class InvalidStatusTransitionException extends DomainException {

    private static final long serialVersionUID = 1L;

    private final DocumentStatus from;
    private final DocumentStatus to;

    public InvalidStatusTransitionException(DocumentStatus from, DocumentStatus to) {
        super("Invalid consultation document status transition: " + from + " -> " + to);
        this.from = from;
        this.to = to;
    }

    public DocumentStatus from() {
        return from;
    }

    public DocumentStatus to() {
        return to;
    }
}
