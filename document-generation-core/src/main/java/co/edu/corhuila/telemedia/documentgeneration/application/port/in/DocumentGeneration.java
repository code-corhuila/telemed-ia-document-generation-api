package co.edu.corhuila.telemedia.documentgeneration.application.port.in;

/** The operational HTTP trigger of PDF generation (POST /generate of document-service.yaml). */
public interface DocumentGeneration {

    /**
     * Creates the PENDING document for a summary, at most once.
     *
     * @throws DocumentAccessDeniedException if the caller is not an administrator
     * @throws co.edu.corhuila.telemedia.documentgeneration.application.port.in.IdempotencyKeyReusedException
     *         if the key was already used with a different request
     */
    GenerationResult request(Caller caller, GenerateDocumentCommand command);
}
