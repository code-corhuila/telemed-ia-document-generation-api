package co.edu.corhuila.telemedia.documentgeneration.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import co.edu.corhuila.telemedia.documentgeneration.adapter.in.background.BackgroundPdfGenerator;
import co.edu.corhuila.telemedia.documentgeneration.adapter.in.background.SchedulingDocumentGeneration;
import co.edu.corhuila.telemedia.documentgeneration.adapter.out.consultation.HttpConsultationSummaryReader;
import co.edu.corhuila.telemedia.documentgeneration.adapter.out.identity.ServiceTokens;
import co.edu.corhuila.telemedia.documentgeneration.adapter.out.pdf.PdfBoxRenderer;
import co.edu.corhuila.telemedia.documentgeneration.adapter.out.persistence.JdbcConsultationDocumentRepository;
import co.edu.corhuila.telemedia.documentgeneration.adapter.out.persistence.JdbcDocumentStatusStore;
import co.edu.corhuila.telemedia.documentgeneration.adapter.out.persistence.JdbcIdempotentDocumentStore;
import co.edu.corhuila.telemedia.documentgeneration.adapter.out.persistence.UuidGenerator;
import co.edu.corhuila.telemedia.documentgeneration.adapter.out.storage.S3DocumentStorage;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DocumentDownloads;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DocumentGeneration;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DocumentPdfGeneration;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DocumentQueries;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.ConsultationDocumentRepository;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.ConsultationSummaryReader;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.DocumentStatusStore;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.DocumentStorage;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.IdGenerator;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.IdempotentDocumentStore;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.PdfRenderer;
import co.edu.corhuila.telemedia.documentgeneration.application.usecase.DocumentDownloadService;
import co.edu.corhuila.telemedia.documentgeneration.application.usecase.DocumentQueryService;
import co.edu.corhuila.telemedia.documentgeneration.application.usecase.PdfGenerationService;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DocumentGenerationApplicationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private Environment environment;

    @Autowired
    private ApplicationContext context;

    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry registry) {
        TestJwt.register(registry);
        TestDatabase.register(registry);
        TestStorage.register(registry);
        TestGeneration.register(registry);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void contextStartsAndHealthIsPublic() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/health", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");
    }

    @Test
    void explicitLimitsAreDeclared() {
        assertThat(environment.getProperty("server.shutdown")).isEqualTo("graceful");
        assertThat(environment.getProperty("server.tomcat.connection-timeout")).isEqualTo("5s");
        assertThat(environment.getProperty("server.tomcat.keep-alive-timeout")).isEqualTo("60s");
        assertThat(environment.getProperty("spring.lifecycle.timeout-per-shutdown-phase")).isEqualTo("20s");
    }

    @Test
    void databaseLimitsAreDeclared() {
        assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("document_generation_app");
        assertThat(environment.getProperty("spring.datasource.hikari.maximum-pool-size")).isEqualTo("10");
        assertThat(environment.getProperty("spring.datasource.hikari.connection-timeout")).isEqualTo("5000");
        assertThat(environment.getProperty("spring.datasource.hikari.connection-init-sql"))
                .isEqualTo("SET statement_timeout = '5s'");
    }

    @Test
    void theCompositionRootWiresTheUseCasesToTheJdbcRepository() {
        assertThat(context.getBean(ConsultationDocumentRepository.class))
                .isInstanceOf(JdbcConsultationDocumentRepository.class);
        assertThat(context.getBean(DocumentQueries.class)).isInstanceOf(DocumentQueryService.class);
        assertThat(context.getBean(IdempotentDocumentStore.class)).isInstanceOf(JdbcIdempotentDocumentStore.class);
        assertThat(context.getBean(IdGenerator.class)).isInstanceOf(UuidGenerator.class);
        assertThat(context.getBean(DocumentGeneration.class)).isInstanceOf(SchedulingDocumentGeneration.class);
        assertThat(context.getBean(DocumentPdfGeneration.class)).isInstanceOf(PdfGenerationService.class);
        assertThat(context.getBean(DocumentStatusStore.class)).isInstanceOf(JdbcDocumentStatusStore.class);
        assertThat(context.getBean(ConsultationSummaryReader.class)).isInstanceOf(HttpConsultationSummaryReader.class);
        // No client secret in these tests: calls go without a token.
        assertThat(context.getBean(ServiceTokens.class)).isSameAs(ServiceTokens.NONE);
        assertThat(context.getBean(PdfRenderer.class)).isInstanceOf(PdfBoxRenderer.class);
        assertThat(context.getBean(BackgroundPdfGenerator.class)).isNotNull();
        assertThat(context.getBean(DocumentStorage.class)).isInstanceOf(S3DocumentStorage.class);
        assertThat(context.getBean(DocumentDownloads.class)).isInstanceOf(DocumentDownloadService.class);
    }

    @Test
    void generationLimitsAreDeclared() {
        // DEC-005: 30 s per attempt; DEC-006: bounded executor, resume sweep, backoff with jitter.
        assertThat(environment.getProperty("generation.attempt-timeout-seconds")).isEqualTo("30");
        assertThat(environment.getProperty("generation.executor-threads")).isEqualTo("2");
        assertThat(environment.getProperty("generation.max-in-flight")).isEqualTo("100");
        assertThat(environment.getProperty("generation.resume-interval-seconds")).isEqualTo("60");
        assertThat(environment.getProperty("generation.resume-batch-size")).isEqualTo("50");
        assertThat(environment.getProperty("generation.retry-backoff-base-ms")).isEqualTo("5000");
        assertThat(environment.getProperty("generation.retry-backoff-cap-ms")).isEqualTo("60000");
        assertThat(environment.getProperty("generation.time-zone")).isEqualTo("America/Bogota");
    }

    @Test
    void consultationClientLimitsAreDeclared() {
        // Norma 5.7.2: bounded retries with backoff; the token is never versioned.
        assertThat(environment.getProperty("consultation.base-url")).isEqualTo("http://localhost:8081");
        assertThat(environment.getProperty("consultation.request-timeout-ms")).isEqualTo("5000");
        assertThat(environment.getProperty("consultation.max-attempts")).isEqualTo("3");
        assertThat(environment.getProperty("consultation.backoff-base-ms")).isEqualTo("200");
        assertThat(environment.getProperty("consultation.backoff-cap-ms")).isEqualTo("2000");
    }

    @Test
    void identityClientSettingsAreDeclared() {
        // identity-and-access-api #117: 10-minute tokens without refresh, renewed a minute before.
        assertThat(environment.getProperty("identity.base-url")).isEqualTo("http://localhost:8082");
        assertThat(environment.getProperty("identity.client-id")).isEqualTo("document-generation");
        assertThat(environment.getProperty("identity.client-secret")).isEmpty();
        assertThat(environment.getProperty("identity.request-timeout-ms")).isEqualTo("5000");
        assertThat(environment.getProperty("identity.refresh-margin-seconds")).isEqualTo("60");
    }

    @Test
    void storageSettingsFollowAdr015() {
        assertThat(environment.getProperty("storage.bucket")).isEqualTo("telemed-documents");
        assertThat(environment.getProperty("storage.region")).isEqualTo("us-east-1");
        assertThat(environment.getProperty("storage.presigned-ttl-seconds")).isEqualTo("300");
    }

    @Test
    void withoutAPublicEndpointUrlsAreSignedForTheInternalOne() {
        assertThat(environment.getProperty("storage.public-endpoint")).isEqualTo("http://localhost:9000");
    }

    @Test
    void theBucketIsEnsuredAtStartupWithoutBlockingIt() {
        // The storage of these tests is not running: the context still starts (ADR-015:
        // storage being down affects downloads only).
        assertThat(context.containsBean("ensureStorageBucket")).isTrue();
    }
}
