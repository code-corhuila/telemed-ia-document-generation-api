package co.edu.corhuila.telemedia.documentgeneration.application.port.out;

import java.util.UUID;

import co.edu.corhuila.telemedia.documentgeneration.domain.model.DomainException;

/** The requested document does not exist (translated to {@code 404 NOT_FOUND}). */
public class DocumentNotFoundException extends DomainException {

    private static final long serialVersionUID = 1L;

    public DocumentNotFoundException(UUID documentId) {
        super("consultation document " + documentId + " does not exist");
    }
}
