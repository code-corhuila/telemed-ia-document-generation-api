package co.edu.corhuila.telemedia.documentgeneration.application.port.out;

/** Reads the optional details of the patient's PDF (DEC-015). */
public interface PatientDetailsReader {

    /** No details at all: the PDF shows the consultation record only. */
    PatientDetailsReader NONE = (summary, correlationId) -> PatientDetails.NONE;

    /**
     * Never fails the generation: what cannot be read right now is {@code null}.
     *
     * @param correlationId sent with every call (norma 5.7.3)
     */
    PatientDetails read(ConsultationSummary summary, String correlationId);
}
