package co.edu.corhuila.telemedia.documentgeneration.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * NFR-005 and the week 11 workshop: Prometheus reads the service metrics on the internal
 * management port, without a token; the public port does not serve them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MetricsEndpointTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private Environment environment;

    @LocalManagementPort
    private int managementPort;

    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry registry) {
        TestJwt.register(registry);
        TestDatabase.register(registry);
        TestStorage.register(registry);
        TestGeneration.register(registry);
    }

    private ResponseEntity<String> scrape() {
        return restTemplate.getForEntity("http://localhost:" + managementPort + "/actuator/prometheus", String.class);
    }

    @Test
    void prometheusReadsTheGoldenSignalsWithoutAToken() {
        restTemplate.getForEntity("/health", String.class);

        ResponseEntity<String> response = scrape();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("document_generation_attempts_seconds_bucket{")
                .contains("outcome=\"available\"")
                .contains("le=\"2.0\"")
                .contains("document_generation_time_to_available_seconds_bucket{")
                .contains("le=\"60.0\"")
                .contains("document_generation_in_flight")
                .contains("http_server_requests_seconds_count{")
                .contains("application=\"document-generation-api\"");
    }

    @Test
    void theHttpLatencyHasTheSloBuckets() {
        restTemplate.getForEntity("/health", String.class);

        assertThat(scrape().getBody()).contains("http_server_requests_seconds_bucket{").contains("le=\"0.5\"");
    }

    @Test
    void thePublicPortDoesNotServeTheMetrics() {
        assertThat(restTemplate.getForEntity("/actuator/prometheus", String.class).getStatusCode())
                .isNotEqualTo(HttpStatus.OK);
    }

    @Test
    void onlyHealthAndPrometheusAreExposed() {
        assertThat(environment.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health,prometheus");
        assertThat(restTemplate.getForEntity("http://localhost:" + managementPort + "/actuator/env", String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
