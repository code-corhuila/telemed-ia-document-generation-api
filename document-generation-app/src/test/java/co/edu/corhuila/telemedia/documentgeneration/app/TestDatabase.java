package co.edu.corhuila.telemedia.documentgeneration.app;

import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * The application requires DB_URL and DB_PASSWORD to start. These context tests do not
 * query the database (the pool opens connections lazily), so a local URL is enough;
 * the real round trip lives in JdbcConsultationDocumentRepositoryIntegrationTest.
 */
final class TestDatabase {

    private TestDatabase() {
    }

    static void register(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", () -> "jdbc:postgresql://localhost:5432/telemed_ia");
        registry.add("DB_PASSWORD", () -> "not-used-by-context-tests");
    }
}
