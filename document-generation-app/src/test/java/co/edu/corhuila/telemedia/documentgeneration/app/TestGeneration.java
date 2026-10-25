package co.edu.corhuila.telemedia.documentgeneration.app;

import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * The application requires the Medical Consultation and identity URLs to start (DEC-006). These
 * context tests never generate a PDF, so the background sweep stays off; the client is
 * tested against a stub in HttpConsultationSummaryReaderTest.
 */
final class TestGeneration {

    private TestGeneration() {
    }

    static void register(DynamicPropertyRegistry registry) {
        registry.add("CONSULTATION_BASE_URL", () -> "http://localhost:8081");
        registry.add("IDENTITY_URL", () -> "http://localhost:8082");
        // Any free management port, so contexts never compete for 9081.
        registry.add("MANAGEMENT_PORT", () -> "0");
        registry.add("GENERATION_BACKGROUND_ENABLED", () -> "false");
        // There is no broker either: the relay is tested in its own classes.
        registry.add("EVENTS_PUBLISHING_ENABLED", () -> "false");
        // Spans are created where a test asks for tracing, but never sent: there is no collector.
        registry.add("management.otlp.tracing.export.enabled", () -> "false");
    }
}
