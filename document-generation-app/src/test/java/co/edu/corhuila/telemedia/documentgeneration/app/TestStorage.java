package co.edu.corhuila.telemedia.documentgeneration.app;

import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * The application requires the storage endpoint and credentials to start (ADR-021). Signing
 * a URL needs no running server, so test values are enough for these context tests; the
 * real round trip lives in S3DocumentStorageIntegrationTest.
 */
final class TestStorage {

    private TestStorage() {
    }

    static void register(DynamicPropertyRegistry registry) {
        registry.add("STORAGE_ENDPOINT", () -> "http://localhost:9000");
        registry.add("STORAGE_ACCESS_KEY", () -> "context-test-access-key");
        registry.add("STORAGE_SECRET_KEY", () -> "context-test-secret-key");
    }
}
