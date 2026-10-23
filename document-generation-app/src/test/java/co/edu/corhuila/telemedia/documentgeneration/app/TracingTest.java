package co.edu.corhuila.telemedia.documentgeneration.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import co.edu.corhuila.telemedia.documentgeneration.application.port.out.ConsultationSummaryReader;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.SummaryUnavailableException;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

/**
 * OBS-DG-02: one trace across services. An incoming W3C {@code traceparent} is continued,
 * every log line carries {@code traceId} and {@code spanId}, the calls to identity and Medical
 * Consultation carry the same trace, and spans go to the platform collector over OTLP.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// Spring Boot turns tracing off in tests unless asked for.
@AutoConfigureObservability
@ExtendWith(OutputCaptureExtension.class)
class TracingTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-00f067aa0ba902b7-01";

    /** Stands in for identity and Medical Consultation; records the trace header of each call. */
    private static final HttpServer DEPENDENCIES = start();
    private static final List<String> RECEIVED = new CopyOnWriteArrayList<>();

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private Tracer tracer;

    @Autowired
    private ConsultationSummaryReader summaries;

    @Autowired
    private Environment environment;

    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry registry) {
        TestJwt.register(registry);
        TestDatabase.register(registry);
        TestStorage.register(registry);
        TestGeneration.register(registry);
        String dependencies = "http://localhost:" + DEPENDENCIES.getAddress().getPort();
        registry.add("CONSULTATION_BASE_URL", () -> dependencies);
        registry.add("IDENTITY_URL", () -> dependencies);
        registry.add("SERVICE_CLIENT_SECRET", () -> "tracing-test-secret");
    }

    @AfterAll
    static void stop() {
        DEPENDENCIES.stop(0);
    }

    @Test
    void anIncomingTraceIsContinuedAndWrittenOnTheLogLines(CapturedOutput output) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("traceparent", TRACEPARENT);

        restTemplate.exchange("/health", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        String accessLine = output.getOut().lines().filter(line -> line.contains("GET /health")).reduce((a, b) -> b)
                .orElseThrow();
        assertThat(accessLine).contains("\"traceId\":\"" + TRACE_ID + "\"").containsPattern("\"spanId\":\"[0-9a-f]{16}\"");
    }

    @Test
    void callsToIdentityAndMedicalConsultationCarryTheSameTrace() {
        RECEIVED.clear();
        Span span = tracer.nextSpan().name("pdf generation attempt").start();
        try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
            assertThatThrownBy(() -> summaries.read(UUID.randomUUID(), "tracing-test"))
                    .isInstanceOf(SummaryUnavailableException.class);
        } finally {
            span.end();
        }

        // One call to identity for the token, one to Medical Consultation for the summary.
        assertThat(RECEIVED).hasSize(2).allSatisfy(traceparent -> assertThat(traceparent)
                .startsWith("00-" + span.context().traceId() + "-")
                .doesNotContain(span.context().spanId()));
    }

    @Test
    void everyRequestIsSampledAndExportedToThePlatformCollector() {
        assertThat(environment.getProperty("management.tracing.sampling.probability")).isEqualTo("1.0");
        assertThat(environment.getProperty("management.otlp.tracing.endpoint"))
                .isEqualTo("http://otel-collector:4318/v1/traces");
    }

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/internal/service-token", exchange -> answer(exchange, 200,
                    "{\"accessToken\":\"tracing-test-token\",\"tokenType\":\"Bearer\",\"expiresAt\":\""
                            + Instant.now().plusSeconds(600) + "\"}"));
            server.createContext("/api/", exchange -> answer(exchange, 404,
                    "{\"error\":\"NOT_FOUND\",\"message\":\"not found\"}"));
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void answer(HttpExchange exchange, int status, String body) throws IOException {
        String traceparent = exchange.getRequestHeaders().getFirst("traceparent");
        RECEIVED.add(traceparent == null ? "none" : traceparent);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
