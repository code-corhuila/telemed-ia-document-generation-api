package co.edu.corhuila.telemedia.documentgeneration.app;

import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * The application requires the MinIO endpoint and credentials to start (ADR-015). Signing
 * a URL needs no running server, so test values are enough for these context tests; the
 * real round trip lives in MinioDocumentStorageIntegrationTest.
 */
final class TestStorage {

    private TestStorage() {
    }

    static void register(DynamicPropertyRegistry registry) {
        registry.add("MINIO_ENDPOINT", () -> "http://localhost:9000");
        registry.add("MINIO_ACCESS_KEY", () -> "context-test-access-key");
        registry.add("MINIO_SECRET_KEY", () -> "context-test-secret-key");
    }
}
