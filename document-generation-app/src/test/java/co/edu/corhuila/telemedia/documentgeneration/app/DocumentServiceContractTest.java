package co.edu.corhuila.telemedia.documentgeneration.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import co.edu.corhuila.telemedia.documentgeneration.adapter.in.background.BackgroundPdfGenerator;

/**
 * Contract checks of Anexo C, numeral 5.3.12, adapted to the documents of this domain, run
 * by HTTP against the running service. As Anexo C sets for the HTTP level, the server runs with
 * an in-memory repository ({@link InMemoryDocuments}), so the checks run everywhere, CI included;
 * the SQL is covered by the JDBC integration tests. Every response is also validated against the
 * published contract {@code 07-api/contracts/openapi/document-service.yaml} (copied under
 * {@code src/test/resources/contract/}).
 *
 * <p>Items of the checklist that do not exist in this domain: money (no amounts), state
 * transitions by HTTP (they are internal, DEC-005), and a {@code createdBefore} filter (not in
 * the contract).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentServiceContractTest {

    private static final String BASE = "/api/documents";
    private static final String ADMIN = "a0000000-0000-4000-8000-00000000000a";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final OpenApiInteractionValidator CONTRACT = OpenApiInteractionValidator
            .createForSpecificationUrl(DocumentServiceContractTest.class.getResource("/contract/document-service.yaml")
                    .toString())
            .withBasePathOverride(BASE)
            // The list schema is an allOf; merged first, so the strict check of unknown
            // properties applies to the whole object and not to each part.
            .withResolveCombinators(true)
            .build();

    /** One patient per run, so the list checks only see this run's documents. */
    private final String patient = UUID.randomUUID().toString();
    private final List<String> created = new ArrayList<>();

    @LocalServerPort
    private int port;

    @Autowired
    private InMemoryDocuments documents;

    @Autowired
    private BackgroundPdfGenerator generator;

    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry registry) {
        TestJwt.register(registry);
        TestDatabase.register(registry);
        TestStorage.register(registry);
        TestGeneration.register(registry);
    }

    /** One in-memory store answers both persistence ports, in place of the JDBC adapters. */
    @TestConfiguration
    static class InMemoryPersistence {

        @Bean
        @Primary
        InMemoryDocuments inMemoryDocuments() {
            return new InMemoryDocuments();
        }
    }

    /** Three PENDING documents of the run's patient, created through the public API. */
    @BeforeAll
    void createDocuments() throws Exception {
        // Documents stay PENDING: no background attempt changes them while the checks run.
        generator.pause();
        for (int i = 0; i < 3; i++) {
            Reply reply = generate(UUID.randomUUID().toString(), patient, "contract-" + UUID.randomUUID());
            assertThat(reply.status()).isEqualTo(201);
            created.add(reply.json().get("id").asText());
        }
    }

    // ---------------------------------------------------------------- Authentication

    @Nested
    class Authentication {

        @Test
        void healthAnswersWithoutAToken() throws Exception {
            assertThat(send(get("/health")).status()).isEqualTo(200);
        }

        @Test
        void withoutATokenItIs401Unauthorized() throws Exception {
            assertError(send(get(BASE)), 401, "UNAUTHORIZED");
        }

        @Test
        void aTokenWithAnotherAlgorithmIs401() throws Exception {
            for (String algorithm : List.of("none", "HS256", "RS512")) {
                assertError(send(get(BASE).header("Authorization",
                        TestJwt.bearerWithAlgorithm(algorithm, patient, "PATIENT"))), 401, "UNAUTHORIZED");
            }
        }

        @Test
        void anExpiredTokenIs401() throws Exception {
            assertError(send(get(BASE).header("Authorization", TestJwt.expiredBearer(patient, "PATIENT"))), 401,
                    "UNAUTHORIZED");
        }

        @Test
        void aTokenSignedWithAnotherKeyIs401() throws Exception {
            assertError(send(get(BASE).header("Authorization", TestJwt.foreignKeyBearer(patient, "PATIENT"))), 401,
                    "UNAUTHORIZED");
        }
    }

    // ------------------------------------------------------ Error envelope and correlation

    @Nested
    class EnvelopeAndCorrelation {

        @Test
        void aMalformedIdIs400ValidationError() throws Exception {
            assertError(send(asPatient(get(BASE + "/not-a-uuid"))), 400, "VALIDATION_ERROR");
        }

        @Test
        void theEnvelopeRepeatsTheReceivedCorrelationIdAsTraceIdAndInTheHeader() throws Exception {
            Reply reply = send(get(BASE).header("X-Correlation-Id", "contract-correlation-1"));

            assertThat(reply.json().get("traceId").asText()).isEqualTo("contract-correlation-1");
            assertThat(reply.header("X-Correlation-Id")).isEqualTo("contract-correlation-1");
            assertThat(reply.json().has("error")).isTrue();
            assertThat(reply.json().has("message")).isTrue();
        }

        @Test
        void withoutACorrelationIdTheServiceGeneratesOne() throws Exception {
            Reply reply = send(get(BASE));

            assertThat(reply.header("X-Correlation-Id")).isNotBlank();
            assertThat(reply.json().get("traceId").asText()).isEqualTo(reply.header("X-Correlation-Id"));
        }

        @Test
        void aMissingDocumentIs404NotFound() throws Exception {
            assertError(send(asPatient(get(BASE + "/" + UUID.randomUUID()))), 404, "NOT_FOUND");
        }

        @Test
        void anUnknownRouteIs404WithTheEnvelope() throws Exception {
            Reply reply = send(asPatient(get("/api/unknown-route")));

            assertThat(reply.status()).isEqualTo(404);
            assertThat(reply.json().get("error").asText()).isEqualTo("NOT_FOUND");
            assertThat(reply.json().has("traceId")).isTrue();
        }

        @Test
        void anotherPatientsDocumentIs403NotA404() throws Exception {
            assertError(send(as(UUID.randomUUID().toString(), "PATIENT", get(BASE + "/" + created.get(0)))), 403,
                    "FORBIDDEN");
        }
    }

    // ------------------------------------------------------------ Validation at the edge

    @Nested
    class Validation {

        @Test
        void anInvalidBodyNamesEveryInvalidFieldIncludingTheIdempotencyKeyHeader() throws Exception {
            Reply reply = send(as(ADMIN, "ADMIN", post(BASE + "/generate", "{\"summaryId\":\"nope\"}")));

            assertError(reply, 400, "VALIDATION_ERROR");
            assertThat(reply.json().get("details").findValuesAsText("field"))
                    .contains("summaryId", "patientId", "Idempotency-Key");
        }

        @Test
        void malformedJsonIs400ValidationError() throws Exception {
            assertError(send(as(ADMIN, "ADMIN", post(BASE + "/generate", "{bad json")
                    .header("Idempotency-Key", "contract-malformed-json"))), 400, "VALIDATION_ERROR");
        }
    }

    // ---------------------------------------------------------------- Idempotent creation

    @Nested
    class IdempotentCreation {

        @Test
        void theFirstRequestIs201WithAUuidAndItsLocation() throws Exception {
            Reply reply = generate(UUID.randomUUID().toString(), patient, "contract-" + UUID.randomUUID());

            assertThat(reply.status()).isEqualTo(201);
            String id = reply.json().get("id").asText();
            assertThat(UUID.fromString(id).toString()).isEqualTo(id);
            assertThat(reply.header("Location")).endsWith("/" + id);
            created.add(id);
        }

        @Test
        void aRetryWithTheSameKeyIs200WithTheSameDocument() throws Exception {
            String summary = UUID.randomUUID().toString();
            String key = "contract-" + UUID.randomUUID();
            Reply first = generate(summary, patient, key);

            Reply retry = generate(summary, patient, key);

            assertThat(retry.status()).isEqualTo(200);
            assertThat(retry.json().get("id").asText()).isEqualTo(first.json().get("id").asText());
            created.add(first.json().get("id").asText());
        }

        @Test
        void theSameKeyWithAnotherBodyIs422() throws Exception {
            String key = "contract-" + UUID.randomUUID();
            created.add(generate(UUID.randomUUID().toString(), patient, key).json().get("id").asText());

            assertError(generate(UUID.randomUUID().toString(), patient, key), 422, "BUSINESS_RULE_VIOLATION");
        }

        @Test
        void theWorkerRequestsAGenerationWithItsServiceToken() throws Exception {
            Reply reply = send(as("worker", "SERVICE", post(BASE + "/generate", body(UUID.randomUUID().toString(), patient))
                    .header("Idempotency-Key", "event-" + UUID.randomUUID())));

            assertThat(reply.status()).isEqualTo(201);
            created.add(reply.json().get("id").asText());
        }

        @Test
        void anotherServiceCannotRequestAGeneration() throws Exception {
            assertError(send(as("notifications", "SERVICE", post(BASE + "/generate",
                    body(UUID.randomUUID().toString(), patient))
                    .header("Idempotency-Key", "event-" + UUID.randomUUID()))), 403, "FORBIDDEN");
        }

        @Test
        void aPatientCannotRequestAGeneration() throws Exception {
            assertError(send(asPatient(post(BASE + "/generate", body(UUID.randomUUID().toString(), patient))
                    .header("Idempotency-Key", "contract-" + UUID.randomUUID()))), 403, "FORBIDDEN");
        }
    }

    // ------------------------------------------------------------------ Representation

    @Nested
    class Representation {

        @Test
        void theDocumentIsCamelCaseWithItsTypesAndRfc3339Dates() throws Exception {
            Reply reply = send(asPatient(get(BASE + "/" + created.get(0))));

            assertThat(reply.status()).isEqualTo(200);
            JsonNode document = reply.json();
            assertThat(document.get("id").asText()).isEqualTo(created.get(0));
            assertThat(document.get("patientId").asText()).isEqualTo(patient);
            assertThat(document.get("format").asText()).isEqualTo("PDF");
            assertThat(document.get("retryCount").isInt()).isTrue();
            assertThat(OffsetDateTime.parse(document.get("createdAt").asText()).getOffset().getTotalSeconds()).isZero();
            assertThat(document.has("storageReference")).isFalse();
            assertThat(document.has("errorMessage")).isFalse();
        }
    }

    // --------------------------------------------- Domain rule (no public state transitions)

    @Nested
    class DownloadRule {

        @Test
        void aDocumentThatIsNotAvailableCannotBeDownloaded() throws Exception {
            assertError(send(asPatient(get(BASE + "/" + created.get(1) + "/download"))), 422,
                    "BUSINESS_RULE_VIOLATION");
        }

        @Test
        void anAvailableDocumentGivesAShortLivedUrl() throws Exception {
            String id = created.get(2);
            documents.markAvailable(UUID.fromString(id));

            Reply reply = send(asPatient(get(BASE + "/" + id + "/download")));

            assertThat(reply.status()).isEqualTo(200);
            assertThat(URI.create(reply.json().get("downloadUrl").asText()).isAbsolute()).isTrue();
            assertThat(OffsetDateTime.parse(reply.json().get("expiresAt").asText())).isAfter(OffsetDateTime.now());
        }
    }

    // ---------------------------------------------------------------------- Bounded list

    @Nested
    class BoundedList {

        @Test
        void theListIsDataAndMetaWithAtMostLimitItems() throws Exception {
            Reply reply = send(asPatient(get(BASE + "?page=1&limit=2")));

            assertThat(reply.status()).isEqualTo(200);
            assertThat(reply.json().get("data").size()).isLessThanOrEqualTo(2);
            assertThat(reply.json().get("meta").get("page").asInt()).isEqualTo(1);
            assertThat(reply.json().get("meta").get("limit").asInt()).isEqualTo(2);
            assertThat(reply.json().get("meta").get("total").asInt()).isGreaterThanOrEqualTo(3);
        }

        @Test
        void withoutLimitTwentyAreUsed() throws Exception {
            assertThat(send(asPatient(get(BASE))).json().get("meta").get("limit").asInt()).isEqualTo(20);
        }

        @Test
        void theListGoesFromTheMostRecentToTheOldest() throws Exception {
            JsonNode data = send(asPatient(get(BASE + "?limit=100"))).json().get("data");

            List<OffsetDateTime> dates = new ArrayList<>();
            data.forEach(document -> dates.add(OffsetDateTime.parse(document.get("createdAt").asText())));
            assertThat(dates).isSortedAccordingTo((a, b) -> b.compareTo(a));
        }

        @Test
        void aLimitAbove100Is400() throws Exception {
            assertError(send(asPatient(get(BASE + "?limit=101"))), 400, "VALIDATION_ERROR");
        }

        @Test
        void anUnknownStatusFilterIs400() throws Exception {
            assertError(send(asPatient(get(BASE + "?status=ARCHIVED"))), 400, "VALIDATION_ERROR");
        }

        @Test
        void theStatusFilterWorks() throws Exception {
            JsonNode data = send(asPatient(get(BASE + "?status=PENDING&limit=100"))).json().get("data");

            assertThat(data.size()).isPositive();
            data.forEach(document -> assertThat(document.get("status").asText()).isEqualTo("PENDING"));
        }
    }

    // ------------------------------------------------------------------------- helpers

    private record Reply(int status, Map<String, List<String>> headers, String body, JsonNode json) {

        String header(String name) {
            return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .map(entry -> entry.getValue().get(0)).findFirst().orElse(null);
        }
    }

    private Reply generate(String summaryId, String patientId, String key) throws Exception {
        return send(as(ADMIN, "ADMIN", post(BASE + "/generate", body(summaryId, patientId))
                .header("Idempotency-Key", key)));
    }

    private static String body(String summaryId, String patientId) {
        return "{\"summaryId\":\"" + summaryId + "\",\"patientId\":\"" + patientId + "\"}";
    }

    private HttpRequest.Builder get(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
    }

    private HttpRequest.Builder post(String path, String body) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private HttpRequest.Builder asPatient(HttpRequest.Builder request) {
        return as(patient, "PATIENT", request);
    }

    private static HttpRequest.Builder as(String subject, String role, HttpRequest.Builder request) {
        return request.header("Authorization", TestJwt.identityBearer(subject, role));
    }

    /**
     * Sends the request and checks the response against the published contract: status code,
     * body schema and headers of the operation, for every response this class receives.
     */
    private static Reply send(HttpRequest.Builder builder) throws IOException, InterruptedException {
        HttpRequest request = builder.build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode json = response.body().isBlank() ? JSON.nullNode() : JSON.readTree(response.body());
        String path = request.uri().getPath();
        if (path.startsWith(BASE)) {
            SimpleResponse.Builder contractResponse = SimpleResponse.Builder.status(response.statusCode())
                    .withBody(response.body());
            response.headers().map().forEach((name, values) -> contractResponse.withHeader(name, values));
            ValidationReport report = CONTRACT.validateResponse(path.equals(BASE) ? BASE + "/" : path,
                    Request.Method.valueOf(request.method()), contractResponse.build());
            assertThat(report.getMessages()).as("%s %s against document-service.yaml", request.method(), path)
                    .isEmpty();
        }
        return new Reply(response.statusCode(), response.headers().map(), response.body(), json);
    }

    private static void assertError(Reply reply, int status, String code) {
        assertThat(reply.status()).as(reply.body()).isEqualTo(status);
        assertThat(reply.json().get("error").asText()).isEqualTo(code);
        assertThat(reply.json().get("message").asText()).isNotBlank();
        assertThat(reply.json().get("traceId").asText()).isNotBlank();
    }
}
