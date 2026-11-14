package co.edu.corhuila.telemedia.documentgeneration.application.port.in;

/** The operational HTTP trigger of PDF generation (POST /generate of document-service.yaml). */
public interface DocumentGeneration {

    /**
     * Checks only who calls, before the request is read: a caller who may not generate gets
     * {@link DocumentAccessDeniedException} whatever the body, so a denied request never
     * learns how a valid one looks. {@link #request} checks the caller again.
     *
     * @throws DocumentAccessDeniedException if the caller is not an administrator or an allowed service
     */
    void authorize(Caller caller);

    /**
     * Creates the PENDING document for a summary, at most once.
     *
     * @throws DocumentAccessDeniedException if the caller is not an administrator
     * @throws co.edu.corhuila.telemedia.documentgeneration.application.port.in.IdempotencyKeyReusedException
     *         if the key was already used with a different request
     */
    GenerationResult request(Caller caller, GenerateDocumentCommand command);
}
