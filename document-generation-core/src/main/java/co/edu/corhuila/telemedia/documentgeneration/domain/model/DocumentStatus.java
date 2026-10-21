package co.edu.corhuila.telemedia.documentgeneration.domain.model;

/**
 * Lifecycle of a consultation document. The allowed transitions are exactly the
 * ones enforced by the database trigger {@code trg_consultation_document_status_transition}.
 */
public enum DocumentStatus {
    PENDING,
    GENERATING,
    AVAILABLE,
    ERROR;

    public boolean canTransitionTo(DocumentStatus target) {
        return switch (this) {
            case PENDING -> target == GENERATING || target == ERROR;
            case GENERATING -> target == AVAILABLE || target == ERROR;
            case ERROR -> target == GENERATING;
            case AVAILABLE -> false;
        };
    }
}
