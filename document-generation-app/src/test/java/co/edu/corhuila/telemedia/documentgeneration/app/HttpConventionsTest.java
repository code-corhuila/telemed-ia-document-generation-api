package co.edu.corhuila.telemedia.documentgeneration.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Anexo C checks on the running service: envelope, correlation, token and JSON log lines. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class HttpConventionsTest {

    private static final String HEADER = "X-Correlation-Id";

    @Autowired
    private TestRestTemplate restTemplate;

    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry registry) {
        TestJwt.register(registry);
        TestDatabase.register(registry);
        TestStorage.register(registry);
        TestGeneration.register(registry);
    }

    private ResponseEntity<Map<String, Object>> getWithTrace(String path, String trace, boolean withToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HEADER, trace);
        if (withToken) {
            headers.set(HttpHeaders.AUTHORIZATION, TestJwt.bearer("patient-1", "PATIENT"));
        }
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers),
                new ParameterizedTypeReference<Map<String, Object>>() { });
    }

    @Test
    void unknownRouteAnswers404WithTheEnvelopeAndTheReceivedTrace() {
        ResponseEntity<Map<String, Object>> response = getWithTrace("/does-not-exist", "e2e-saga-1", true);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getFirst(HEADER)).isEqualTo("e2e-saga-1");
        assertThat(response.getBody())
                .containsEntry("error", "NOT_FOUND")
                .containsEntry("traceId", "e2e-saga-1")
                .containsKey("message")
                .doesNotContainKey("details");
    }

    @Test
    void aProtectedRouteWithoutTokenAnswers401WithTheEnvelope() {
        ResponseEntity<Map<String, Object>> response = getWithTrace("/does-not-exist", "no-token-1", false);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody())
                .containsEntry("error", "UNAUTHORIZED")
                .containsEntry("traceId", "no-token-1");
    }

    @Test
    void withoutTheHeaderTheServiceGeneratesOne() {
        ResponseEntity<String> response = restTemplate.getForEntity("/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(UUID.fromString(response.getHeaders().getFirst(HEADER))).isNotNull();
    }

    @Test
    void everyRequestWritesAJsonLogLineWithItsCorrelationId(CapturedOutput output) {
        getWithTrace("/health", "log-line-check-1", false);

        // The access line is written once the response is sent, so wait for it briefly.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(output.getOut().lines()
                .filter(line -> line.contains("log-line-check-1")))
                .isNotEmpty()
                .allSatisfy(line -> {
                    assertThat(line).startsWith("{");
                    assertThat(line).contains("\"correlationId\":\"log-line-check-1\"");
                }));
    }
}
