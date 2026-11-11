package co.edu.corhuila.telemedia.documentgeneration.application.port.out;

/**
 * DEC-015: what the patient's PDF shows besides the consultation record. Every field is
 * optional ({@code null}): names from identity (auth DEC-014), the reason for the visit from
 * the agent (agent DEC-019).
 */
public record PatientDetails(String patientName, String professionalName, String reasonForVisit) {

    public static final PatientDetails NONE = new PatientDetails(null, null, null);
}
