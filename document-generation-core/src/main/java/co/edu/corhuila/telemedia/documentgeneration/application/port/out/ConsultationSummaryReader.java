package co.edu.corhuila.telemedia.documentgeneration.application.port.out;

import java.util.UUID;

/** Reads the summary from its owner, Medical Consultation, through its API (DEC-006). */
public interface ConsultationSummaryReader {

    /**
     * @throws SummaryUnavailableException if the summary cannot be read right now
     */
    ConsultationSummary read(UUID summaryId);
}
