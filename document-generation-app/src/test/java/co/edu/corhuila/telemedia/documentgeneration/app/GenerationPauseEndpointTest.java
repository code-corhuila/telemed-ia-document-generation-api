package co.edu.corhuila.telemedia.documentgeneration.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import co.edu.corhuila.telemedia.documentgeneration.adapter.in.background.BackgroundPdfGenerator;

/**
 * OBS-DG-06: the on-call engineer pauses and resumes PDF generation without stopping the
 * service, from the internal management port; the pause shows as a metric.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// Spring Boot turns metrics export off in tests unless asked for.
@AutoConfigureObservability
class GenerationPauseEndpointTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() { };

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private BackgroundPdfGenerator generator;

    @LocalManagementPort
    private int managementPort;

    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry registry) {
        TestJwt.register(registry);
        TestDatabase.register(registry);
        TestStorage.register(registry);
        TestGeneration.register(registry);
    }

    @AfterEach
    void resume() {
        generator.resume();
    }

    private String endpoint() {
        return "http://localhost:" + managementPort + "/actuator/generation";
    }

    private ResponseEntity<Map<String, Object>> setPaused(boolean paused) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(endpoint(), HttpMethod.POST,
                new HttpEntity<>("{\"paused\":" + paused + "}", headers), JSON);
    }

    @Test
    void generationRunsUntilItIsPaused() {
        ResponseEntity<Map<String, Object>> state = restTemplate.exchange(endpoint(), HttpMethod.GET, null, JSON);

        assertThat(state.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(state.getBody()).containsEntry("paused", false);
    }

    @Test
    void theOnCallEngineerPausesAndResumesGeneration() {
        assertThat(setPaused(true).getBody()).containsEntry("paused", true);
        assertThat(generator.isPaused()).isTrue();
        assertThat(restTemplate.exchange(endpoint(), HttpMethod.GET, null, JSON).getBody())
                .containsEntry("paused", true);

        assertThat(setPaused(false).getBody()).containsEntry("paused", false);
        assertThat(generator.isPaused()).isFalse();
    }

    @Test
    void thePauseIsAMetric() {
        setPaused(true);

        String metrics = restTemplate.getForObject("http://localhost:" + managementPort + "/actuator/prometheus",
                String.class);

        assertThat(metrics).containsPattern("document_generation_paused\\{[^}]*\\} 1\\.0");
    }

    @Test
    void thePublicPortCannotPauseGeneration() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.exchange("/actuator/generation", HttpMethod.POST,
                new HttpEntity<>("{\"paused\":true}", headers), String.class);

        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.OK);
        assertThat(generator.isPaused()).isFalse();
    }
}
