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

import co.edu.corhuila.telemedia.documentgeneration.adapter.out.persistence.JdbcConsultationDocumentRepository;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DocumentQueries;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.ConsultationDocumentRepository;
import co.edu.corhuila.telemedia.documentgeneration.application.usecase.DocumentQueryService;

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
    }
}
