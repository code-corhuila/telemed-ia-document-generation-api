package co.edu.corhuila.telemedia.documentgeneration.application.port.out;

import java.util.UUID;

/** Reads the summary from its owner, Medical Consultation, through its API (DEC-006). */
public interface ConsultationSummaryReader {

    /**
     * @param correlationId sent with every call, so the attempt is traced across services
     *        (norma 5.7.3)
     * @throws SummaryUnavailableException if the summary cannot be read right now
     */
    ConsultationSummary read(UUID summaryId, String correlationId);
}
