package co.edu.corhuila.telemedia.documentgeneration.app;

import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * The application requires the Medical Consultation base URL to start (DEC-006). These
 * context tests never generate a PDF, so the background sweep stays off; the client is
 * tested against a stub in HttpConsultationSummaryReaderTest.
 */
final class TestGeneration {

    private TestGeneration() {
    }

    static void register(DynamicPropertyRegistry registry) {
        registry.add("CONSULTATION_BASE_URL", () -> "http://localhost:8081");
        registry.add("GENERATION_BACKGROUND_ENABLED", () -> "false");
    }
}
