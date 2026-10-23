package co.edu.corhuila.telemedia.documentgeneration.adapter.out.consultation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;

import co.edu.corhuila.telemedia.documentgeneration.adapter.out.consultation.HttpConsultationSummaryReader.Settings;
import co.edu.corhuila.telemedia.documentgeneration.adapter.out.identity.ServiceTokenException;
import co.edu.corhuila.telemedia.documentgeneration.adapter.out.identity.ServiceTokens;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.ConsultationSummary;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.ConsultationSummary.Medication;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.SummaryUnavailableException;

/**
 * Against a stub of the Medical Consultation service: its routes
 * ({@code GET /api/post-summaries/{id}}, {@code GET /api/consultations/{id}/attention}),
 * its {@code {data}} envelope and the schemas of consultation-service.yaml.
 */
class HttpConsultationSummaryReaderTest {

    private static final UUID SUMMARY = UUID.fromString("e0000000-0000-0000-0000-000000000102");
    private static final UUID CONSULTATION = UUID.fromString("00000000-0000-0000-0000-000000009001");
    private static final UUID PATIENT = UUID.fromString("b0000000-0000-0000-0000-000000000001");
    private static final String POST_PATH = "/api/post-summaries/" + SUMMARY;
    private static final String ATTENTION_PATH = "/api/consultations/" + CONSULTATION + "/attention";
    private static final String TRACE = "trace-123";

    private static final String POST_SUMMARY = """
            {"data": {"id": "%s", "consultationId": "%s", "appointmentId": "a0000000-0000-0000-0000-000000000001",
                      "patientId": "%s", "attentionSummaryId": "11111111-1111-1111-1111-111111111111",
                      "generatedAt": "2026-10-23T14:00:00Z"}}
            """.formatted(SUMMARY, CONSULTATION, PATIENT);

    private static final String ATTENTION = """
            {"data": {"id": "11111111-1111-1111-1111-111111111111", "consultationId": "%s",
                      "diagnosis": "Rinofaringitis aguda", "recommendations": "Reposo e hidratación",
                      "medications": [
                        {"name": "Acetaminofén", "dosage": "500 mg", "frequency": "cada 8 horas",
                         "duration": "5 días", "instructions": "con alimentos"},
                        {"name": "Loratadina"}],
                      "observations": "Estable", "referral": "Otorrinolaringología",
                      "followUp": {"control": "15 días", "inDays": 15, "notes": null},
                      "createdAt": "2026-10-23T13:00:00Z", "updatedAt": "2026-10-23T13:00:00Z"}}
            """.formatted(CONSULTATION);

    private HttpServer server;
    private final Map<String, ConcurrentLinkedDeque<Answer>> answers = new ConcurrentHashMap<>();
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final List<Duration> waits = new ArrayList<>();
    private final FakeTokens tokens = new FakeTokens();

    private record Answer(int status, String body, long delayMillis) { }

    private record Received(String path, Headers headers) { }

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            received.add(new Received(path, exchange.getRequestHeaders()));
            ConcurrentLinkedDeque<Answer> queue = answers.getOrDefault(path, new ConcurrentLinkedDeque<>());
            Answer answer = queue.size() > 1 ? queue.poll() : queue.peek();
            if (answer == null) {
                answer = new Answer(404, "{\"error\":\"NOT_FOUND\",\"message\":\"not found\"}", 0);
            }
            try {
                Thread.sleep(answer.delayMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = answer.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(answer.status(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        // Concurrent, so a slow answer does not hold back the retry behind it.
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    /** Answers in order; the last one repeats. */
    private void answer(String path, Answer... sequence) {
        answers.put(path, new ConcurrentLinkedDeque<>(List.of(sequence)));
    }

    private static Answer ok(String body) {
        return new Answer(200, body, 0);
    }

    private static Answer status(int status) {
        return new Answer(status, "{\"error\":\"E\",\"message\":\"diagnosis: confidential\"}", 0);
    }

    private URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private HttpConsultationSummaryReader reader(ServiceTokens serviceTokens, int maxAttempts, double random) {
        Settings settings = new Settings(baseUrl(), Duration.ofMillis(500), maxAttempts, Duration.ofMillis(100),
                Duration.ofMillis(250));
        return new HttpConsultationSummaryReader(HttpClient.newHttpClient(), settings, serviceTokens, waits::add,
                () -> random);
    }

    private HttpConsultationSummaryReader reader(int maxAttempts, double random) {
        return reader(tokens, maxAttempts, random);
    }

    private HttpConsultationSummaryReader reader() {
        return reader(tokens, 3, 1.0);
    }

    private long requestsTo(String path) {
        return received.stream().filter(r -> r.path().equals(path)).count();
    }

    @Test
    void readsThePostSummaryThenItsAttentionSummary() {
        answer(POST_PATH, ok(POST_SUMMARY));
        answer(ATTENTION_PATH, ok(ATTENTION));

        ConsultationSummary summary = reader().read(SUMMARY, TRACE);

        assertThat(summary).isEqualTo(new ConsultationSummary(SUMMARY, CONSULTATION, PATIENT,
                "Rinofaringitis aguda", "Reposo e hidratación",
                List.of(new Medication("Acetaminofén", "500 mg", "cada 8 horas", "5 días"),
                        new Medication("Loratadina", null, null, null)),
                "Estable", "Otorrinolaringología", Map.of("control", "15 días", "inDays", "15")));
        assertThat(received).extracting(Received::path).containsExactly(POST_PATH, ATTENTION_PATH);
        assertThat(waits).isEmpty();
    }

    @Test
    void everyCallCarriesTheServiceTokenAndTheCorrelationId() {
        answer(POST_PATH, ok(POST_SUMMARY));
        answer(ATTENTION_PATH, ok(ATTENTION));

        reader().read(SUMMARY, TRACE);

        assertThat(received).hasSize(2).allSatisfy(request -> {
            assertThat(request.headers().getFirst("Authorization")).isEqualTo("Bearer service-token-1");
            assertThat(request.headers().getFirst("X-Correlation-Id")).isEqualTo(TRACE);
            assertThat(request.headers().getFirst("Accept")).isEqualTo("application/json");
        });
    }

    @Test
    void withoutAConfiguredTokenNoAuthorizationIsSent() {
        answer(POST_PATH, ok(POST_SUMMARY));
        answer(ATTENTION_PATH, ok(ATTENTION));

        reader(ServiceTokens.NONE, 3, 1.0).read(SUMMARY, TRACE);

        assertThat(received).allSatisfy(request -> assertThat(request.headers().containsKey("Authorization")).isFalse());
    }

    @Test
    void aFollowUpSentAsTextIsKept() {
        answer(POST_PATH, ok(POST_SUMMARY));
        answer(ATTENTION_PATH, ok(ATTENTION.replace(
                "{\"control\": \"15 días\", \"inDays\": 15, \"notes\": null}", "\"Control en 15 días\"")));

        assertThat(reader().read(SUMMARY, TRACE).followUp()).isEqualTo(Map.of("indicaciones", "Control en 15 días"));
    }

    @Test
    void absentOptionalFieldsAreEmpty() {
        answer(POST_PATH, ok(POST_SUMMARY));
        answer(ATTENTION_PATH, ok("""
                {"data": {"id": "11111111-1111-1111-1111-111111111111", "consultationId": "%s",
                          "recommendations": "Reposo", "diagnosis": null}}
                """.formatted(CONSULTATION)));

        ConsultationSummary summary = reader().read(SUMMARY, TRACE);

        assertThat(summary.diagnosis()).isNull();
        assertThat(summary.observations()).isNull();
        assertThat(summary.referral()).isNull();
        assertThat(summary.medications()).isEmpty();
        assertThat(summary.followUp()).isEmpty();
    }

    @Test
    void aServerErrorIsRetriedAndThenSucceeds() {
        answer(POST_PATH, status(503), ok(POST_SUMMARY));
        answer(ATTENTION_PATH, ok(ATTENTION));

        assertThat(reader().read(SUMMARY, TRACE).summaryId()).isEqualTo(SUMMARY);

        assertThat(requestsTo(POST_PATH)).isEqualTo(2);
        assertThat(waits).hasSize(1);
    }

    @Test
    void tooManyRequestsIsRetried() {
        answer(POST_PATH, ok(POST_SUMMARY));
        answer(ATTENTION_PATH, status(429), ok(ATTENTION));

        assertThat(reader().read(SUMMARY, TRACE).recommendations()).isEqualTo("Reposo e hidratación");

        assertThat(requestsTo(ATTENTION_PATH)).isEqualTo(2);
    }

    @Test
    void aRequestOverItsTimeoutIsRetried() {
        answer(POST_PATH, new Answer(200, POST_SUMMARY, 1_500), ok(POST_SUMMARY));
        answer(ATTENTION_PATH, ok(ATTENTION));

        assertThat(reader().read(SUMMARY, TRACE).summaryId()).isEqualTo(SUMMARY);

        assertThat(waits).hasSize(1);
    }

    @Test
    void retriesStopAtTheLimitWithExponentialBackoffAndFullJitter() {
        answer(POST_PATH, status(500));

        assertThatThrownBy(() -> reader(4, 1.0).read(SUMMARY, TRACE))
                .isInstanceOf(SummaryUnavailableException.class);

        assertThat(requestsTo(POST_PATH)).isEqualTo(4);
        // base 100 ms doubling, capped at 250 ms; full jitter scales each wait.
        assertThat(waits).containsExactly(Duration.ofMillis(100), Duration.ofMillis(200), Duration.ofMillis(250));
    }

    @Test
    void theJitterScalesTheWait() {
        answer(POST_PATH, status(500));

        assertThatThrownBy(() -> reader(3, 0.5).read(SUMMARY, TRACE))
                .isInstanceOf(SummaryUnavailableException.class);

        assertThat(waits).containsExactly(Duration.ofMillis(50), Duration.ofMillis(100));
    }

    @Test
    void anUnreachableServiceIsRetriedAndThenUnavailable() {
        Settings settings = new Settings(URI.create("http://127.0.0.1:1"), Duration.ofMillis(500), 3,
                Duration.ofMillis(100), Duration.ofMillis(250));
        HttpConsultationSummaryReader unreachable = new HttpConsultationSummaryReader(HttpClient.newHttpClient(),
                settings, tokens, waits::add, () -> 1.0);

        assertThatThrownBy(() -> unreachable.read(SUMMARY, TRACE)).isInstanceOf(SummaryUnavailableException.class);

        assertThat(waits).hasSize(2);
    }

    @Test
    void clientErrorsAreNotRetried() {
        for (int code : new int[] {400, 401, 403, 404}) {
            received.clear();
            answer(POST_PATH, status(code));

            assertThatThrownBy(() -> reader().read(SUMMARY, TRACE)).isInstanceOf(SummaryUnavailableException.class);

            assertThat(requestsTo(POST_PATH)).as("status %d", code).isEqualTo(1);
        }
        assertThat(waits).isEmpty();
    }

    @Test
    void theErrorNeverCarriesTheResponseBody() {
        answer(POST_PATH, status(500));

        assertThatThrownBy(() -> reader().read(SUMMARY, TRACE))
                .isInstanceOf(SummaryUnavailableException.class)
                .hasMessageNotContaining("confidential");
    }

    @Test
    void aResponseOutsideTheContractIsUnavailableAndNotRetried() {
        List<String> invalidPostSummaries = List.of(
                "not json",
                "{\"id\": \"%s\"}".formatted(SUMMARY),
                POST_SUMMARY.replace(PATIENT.toString(), "patient-1"),
                POST_SUMMARY.replace("\"consultationId\": \"%s\",".formatted(CONSULTATION), ""));
        for (String body : invalidPostSummaries) {
            received.clear();
            answer(POST_PATH, ok(body));
            answer(ATTENTION_PATH, ok(ATTENTION));

            assertThatThrownBy(() -> reader().read(SUMMARY, TRACE)).as(body)
                    .isInstanceOf(SummaryUnavailableException.class);
            assertThat(requestsTo(POST_PATH)).isEqualTo(1);
        }
        assertThat(waits).isEmpty();
    }

    @Test
    void anAttentionSummaryOutsideTheContractIsUnavailable() {
        List<String> invalidAttentions = List.of(
                ATTENTION.replace("\"recommendations\": \"Reposo e hidratación\",", ""),
                ATTENTION.replace("{\"name\": \"Loratadina\"}", "{\"dosage\": \"10 mg\"}"),
                ATTENTION.replace("\"diagnosis\": \"Rinofaringitis aguda\"", "\"diagnosis\": 42"));
        answer(POST_PATH, ok(POST_SUMMARY));
        for (String body : invalidAttentions) {
            answer(ATTENTION_PATH, ok(body));

            assertThatThrownBy(() -> reader().read(SUMMARY, TRACE)).isInstanceOf(SummaryUnavailableException.class);
        }
    }

    @Test
    void anAttentionSummaryOfAnotherConsultationIsUnavailable() {
        answer(POST_PATH, ok(POST_SUMMARY));
        answer(ATTENTION_PATH, ok(ATTENTION.replace(CONSULTATION.toString(), UUID.randomUUID().toString())));

        assertThatThrownBy(() -> reader().read(SUMMARY, TRACE)).isInstanceOf(SummaryUnavailableException.class);
    }

    @Test
    void aSummaryThatCannotBeReadIsLoggedWithTheReasonButNeverTheBody() {
        answer(POST_PATH, status(404));
        Logger logger = (Logger) LoggerFactory.getLogger(HttpConsultationSummaryReader.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            assertThatThrownBy(() -> reader().read(SUMMARY, TRACE)).isInstanceOf(SummaryUnavailableException.class);
        } finally {
            logger.detachAppender(logs);
        }

        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains(SUMMARY.toString(), "answered 404")
                    .doesNotContain("confidential");
        });
    }

    @Test
    void aRejectedTokenIsRenewedOnceAndTheCallRepeatedAtOnce() {
        answer(POST_PATH, status(401), ok(POST_SUMMARY));
        answer(ATTENTION_PATH, ok(ATTENTION));

        assertThat(reader().read(SUMMARY, TRACE).summaryId()).isEqualTo(SUMMARY);

        assertThat(tokens.invalidated).containsExactly("service-token-1");
        assertThat(received).extracting(r -> r.headers().getFirst("Authorization"))
                .containsExactly("Bearer service-token-1", "Bearer service-token-2", "Bearer service-token-2");
        assertThat(waits).isEmpty();
    }

    @Test
    void aRenewedTokenThatIsAlsoRejectedIsUnavailable() {
        answer(POST_PATH, status(401));

        assertThatThrownBy(() -> reader().read(SUMMARY, TRACE)).isInstanceOf(SummaryUnavailableException.class)
                .hasMessageContaining("401");

        assertThat(requestsTo(POST_PATH)).isEqualTo(2);
        assertThat(tokens.invalidated).hasSize(1);
    }

    @Test
    void withoutCredentialsA401IsNotRepeated() {
        answer(POST_PATH, status(401));

        assertThatThrownBy(() -> reader(ServiceTokens.NONE, 3, 1.0).read(SUMMARY, TRACE))
                .isInstanceOf(SummaryUnavailableException.class);

        assertThat(requestsTo(POST_PATH)).isEqualTo(1);
    }

    @Test
    void anIdentityServiceThatIsDownIsRetriedWithBackoff() {
        tokens.failures.add(new ServiceTokenException("the identity service answered 503", true, null));
        answer(POST_PATH, ok(POST_SUMMARY));
        answer(ATTENTION_PATH, ok(ATTENTION));

        assertThat(reader().read(SUMMARY, TRACE).summaryId()).isEqualTo(SUMMARY);

        assertThat(waits).containsExactly(Duration.ofMillis(100));
    }

    @Test
    void credentialsTheIdentityServiceRejectsAreNotRetried() {
        tokens.failures.add(new ServiceTokenException("the identity service answered 401", false, null));

        assertThatThrownBy(() -> reader().read(SUMMARY, TRACE)).isInstanceOf(SummaryUnavailableException.class)
                .hasMessageContaining("service token");

        assertThat(received).isEmpty();
        assertThat(waits).isEmpty();
    }

    @Test
    void theSettingsAreValidated() {
        Duration second = Duration.ofSeconds(1);
        assertThatThrownBy(() -> new Settings(URI.create("/relative"), second, 3, second, second))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Settings(baseUrl(), Duration.ZERO, 3, second, second))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Settings(baseUrl(), second, 0, second, second))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Settings(baseUrl(), second, 3, second, Duration.ofMillis(10)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Issues service-token-1, service-token-2... and renews only after a rejection. */
    private static final class FakeTokens implements ServiceTokens {

        private final List<String> invalidated = new ArrayList<>();
        private final Deque<ServiceTokenException> failures = new ArrayDeque<>();
        private int issued = 1;

        @Override
        public String token() {
            if (!failures.isEmpty()) {
                throw failures.poll();
            }
            return "service-token-" + issued;
        }

        @Override
        public void invalidate(String rejected) {
            invalidated.add(rejected);
            issued++;
        }
    }
}
