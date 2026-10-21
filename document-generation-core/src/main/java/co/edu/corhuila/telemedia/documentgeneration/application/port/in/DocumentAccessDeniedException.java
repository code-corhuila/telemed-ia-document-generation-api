package co.edu.corhuila.telemedia.documentgeneration.application.port.in;

import co.edu.corhuila.telemedia.documentgeneration.domain.model.DomainException;

/**
 * DEC-004: only a patient may read documents, and only their own
 * (translated to {@code 403 FORBIDDEN}).
 */
public class DocumentAccessDeniedException extends DomainException {

    private static final long serialVersionUID = 1L;

    public DocumentAccessDeniedException() {
        super("the caller cannot access this document");
    }
}
