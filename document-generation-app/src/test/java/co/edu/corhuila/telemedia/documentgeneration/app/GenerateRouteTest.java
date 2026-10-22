package co.edu.corhuila.telemedia.documentgeneration.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * POST /generate on the running service with tokens shaped as the identity service issues
 * them. Only paths that end before the database are exercised here; the persistence round
 * trip lives in JdbcIdempotentDocumentStoreIntegrationTest.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GenerateRouteTest {

    private static final String ROUTE = "/api/documents/generate";
    private static final String BODY = "{\"summaryId\":\"5a1b2c3d-5555-4555-8555-555555555555\","
            + "\"patientId\":\"c1b2c3d4-3333-4333-8333-333333333333\"}";

    @Autowired
    private TestRestTemplate restTemplate;

    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry registry) {
        TestJwt.register(registry);
        TestDatabase.register(registry);
        TestStorage.register(registry);
    }

    private ResponseEntity<Map<String, Object>> generate(String role, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, TestJwt.identityBearer("c1b2c3d4-3333-4333-8333-333333333333", role));
        headers.set("Idempotency-Key", "route-test-0001");
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(ROUTE, HttpMethod.POST, new HttpEntity<>(body, headers),
                new ParameterizedTypeReference<Map<String, Object>>() { });
    }

    @Test
    void aPatientTokenFromIdentityIs403() {
        ResponseEntity<Map<String, Object>> response = generate("PATIENT", BODY);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("error", "FORBIDDEN").containsKey("traceId");
    }

    @Test
    void anUnknownPropertyIsRejectedWithTheApplicationJsonMapper() {
        ResponseEntity<Map<String, Object>> response = generate("ADMIN",
                BODY.replace("}", ",\"retryCount\":3}"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "VALIDATION_ERROR");
        assertThat(response.getBody().get("details"))
                .isEqualTo(List.of(Map.of("field", "retryCount", "message", "is not allowed")));
    }
}
