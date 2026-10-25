package co.edu.corhuila.telemedia.documentgeneration.application.port.out;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The post-consultation summary as Document Generation sees it: the identifiers of
 * {@code PostSummary} and the clinical content of {@code AttentionSummary} owned by
 * Medical Consultation (consultation-service.yaml). Optional fields are {@code null}.
 */
public record ConsultationSummary(
        UUID summaryId,
        UUID consultationId,
        UUID appointmentId,
        UUID patientId,
        String diagnosis,
        String recommendations,
        List<Medication> medications,
        String observations,
        String referral,
        Map<String, String> followUp) {

    public ConsultationSummary {
        Objects.requireNonNull(summaryId, "summaryId");
        Objects.requireNonNull(consultationId, "consultationId");
        Objects.requireNonNull(appointmentId, "appointmentId");
        Objects.requireNonNull(patientId, "patientId");
        Objects.requireNonNull(recommendations, "recommendations");
        medications = medications == null ? List.of() : List.copyOf(medications);
        followUp = followUp == null ? Map.of() : Map.copyOf(followUp);
    }

    /** {@code Medication} of the contract: only the name is required. */
    public record Medication(String name, String dosage, String frequency, String duration) {

        public Medication {
            Objects.requireNonNull(name, "name");
        }
    }
}
