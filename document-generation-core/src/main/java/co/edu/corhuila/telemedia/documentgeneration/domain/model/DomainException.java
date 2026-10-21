package co.edu.corhuila.telemedia.documentgeneration.domain.model;

/**
 * Base type of every typed domain error. The domain does not know HTTP status
 * codes: the HTTP adapter translates each subtype in a single place (Anexo C).
 */
public abstract class DomainException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    protected DomainException(String message) {
        super(message);
    }
}
