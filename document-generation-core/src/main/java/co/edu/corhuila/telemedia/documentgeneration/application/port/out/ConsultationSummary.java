package co.edu.corhuila.telemedia.documentgeneration.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The post-consultation summary as Document Generation sees it: the identifiers of
 * {@code PostSummary} and the clinical content of {@code AttentionSummary} owned by
 * Medical Consultation (consultation-service.yaml). Optional fields are {@code null};
 * {@code professionalId} and {@code completedAt} come with consultation DEC-008.
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
        Map<String, String> followUp,
        UUID professionalId,
        Instant completedAt) {

    public ConsultationSummary {
        Objects.requireNonNull(summaryId, "summaryId");
        Objects.requireNonNull(consultationId, "consultationId");
        Objects.requireNonNull(appointmentId, "appointmentId");
        Objects.requireNonNull(patientId, "patientId");
        Objects.requireNonNull(recommendations, "recommendations");
        medications = medications == null ? List.of() : List.copyOf(medications);
        followUp = followUp == null ? Map.of() : Map.copyOf(followUp);
    }

    /** A summary without the DEC-008 fields (who attended and when). */
    public ConsultationSummary(UUID summaryId, UUID consultationId, UUID appointmentId, UUID patientId,
            String diagnosis, String recommendations, List<Medication> medications, String observations,
            String referral, Map<String, String> followUp) {
        this(summaryId, consultationId, appointmentId, patientId, diagnosis, recommendations, medications,
                observations, referral, followUp, null, null);
    }

    /** {@code Medication} of the contract: only the name is required. */
    public record Medication(String name, String dosage, String frequency, String duration, String instructions) {

        public Medication {
            Objects.requireNonNull(name, "name");
        }

        public Medication(String name, String dosage, String frequency, String duration) {
            this(name, dosage, frequency, duration, null);
        }
    }
}
