package co.edu.corhuila.telemedia.documentgeneration.adapter.out.consultation;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import co.edu.corhuila.telemedia.documentgeneration.adapter.out.identity.ServiceTokenException;
import co.edu.corhuila.telemedia.documentgeneration.adapter.out.identity.ServiceTokens;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.ConsultationSummary;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.ConsultationSummary.Medication;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.ConsultationSummaryReader;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.SummaryUnavailableException;

/**
 * Reads the summary from Medical Consultation through its API (DEC-006, Anexo J: never its
 * tables): {@code GET /api/post-summaries/{summaryId}} gives the consultation, and
 * {@code GET /api/consultations/{consultationId}/attention} the clinical content
 * ({@code AttentionSummary} of consultation-service.yaml), both inside {@code {data}}.
 *
 * <p>Every call carries the service token and the correlation id (norma 5.7.3) and has its
 * own timeout. A {@code 401} is answered once with a new token, at once: the token may have
 * expired or been revoked early. Only network failures, {@code 429} and {@code 5xx} are retried, a bounded
 * number of times with exponential backoff and full jitter (norma 5.7.2). Error messages
 * name the status only: the response body may carry clinical data.
 */
public final class HttpConsultationSummaryReader implements ConsultationSummaryReader {

    /** Key of a follow-up the service sends as plain text instead of an object. */
    static final String FOLLOW_UP_TEXT = "indicaciones";

    private static final Logger LOG = LoggerFactory.getLogger(HttpConsultationSummaryReader.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * @param backoffBase wait before the first retry, doubled on each one up to {@code backoffCap}
     */
    public record Settings(URI baseUrl, Duration requestTimeout, int maxAttempts, Duration backoffBase,
            Duration backoffCap) {

        public Settings {
            Objects.requireNonNull(baseUrl, "baseUrl");
            Objects.requireNonNull(requestTimeout, "requestTimeout");
            Objects.requireNonNull(backoffBase, "backoffBase");
            Objects.requireNonNull(backoffCap, "backoffCap");
            if (!"http".equals(baseUrl.getScheme()) && !"https".equals(baseUrl.getScheme())) {
                throw new IllegalArgumentException("the consultation base URL must be absolute http(s)");
            }
            if (!requestTimeout.isPositive() || !backoffBase.isPositive()) {
                throw new IllegalArgumentException("the timeout and the backoff must be positive");
            }
            if (backoffCap.compareTo(backoffBase) < 0) {
                throw new IllegalArgumentException("the backoff cap must not be below its base");
            }
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("at least one attempt is required");
            }
        }
    }

    /** Waits between attempts; replaced in tests. */
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final HttpClient http;
    private final Settings settings;
    private final ServiceTokens tokens;
    private final String base;
    private final Sleeper sleeper;
    private final DoubleSupplier random;

    /** @param tokens the token of this service (norma 5.7), issued by the identity service */
    public HttpConsultationSummaryReader(HttpClient http, Settings settings, ServiceTokens tokens) {
        this(http, settings, tokens, Thread::sleep, () -> ThreadLocalRandom.current().nextDouble());
    }

    HttpConsultationSummaryReader(HttpClient http, Settings settings, ServiceTokens tokens, Sleeper sleeper,
            DoubleSupplier random) {
        this.http = Objects.requireNonNull(http, "http");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.random = Objects.requireNonNull(random, "random");
        String url = settings.baseUrl().toString();
        this.base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** The document only stores a neutral message, so the reason is logged here. */
    @Override
    public ConsultationSummary read(UUID summaryId, String correlationId) {
        Objects.requireNonNull(summaryId, "summaryId");
        Objects.requireNonNull(correlationId, "correlationId");
        try {
            return fetch(summaryId, correlationId);
        } catch (SummaryUnavailableException e) {
            LOG.warn("summary {} could not be read: {}", summaryId, e.getMessage());
            throw e;
        }
    }

    private ConsultationSummary fetch(UUID summaryId, String correlationId) {
        JsonNode postSummary = get("/api/post-summaries/" + summaryId, correlationId);
        UUID consultationId = uuid(postSummary, "consultationId");
        JsonNode attention = get("/api/consultations/" + consultationId + "/attention", correlationId);
        if (attention.hasNonNull("consultationId") && !consultationId.equals(uuid(attention, "consultationId"))) {
            throw invalid("the attention summary belongs to another consultation");
        }
        return new ConsultationSummary(uuid(postSummary, "id"), consultationId, uuid(postSummary, "patientId"),
                text(attention, "diagnosis"), required(text(attention, "recommendations"), "recommendations"),
                medications(attention.get("medications")), text(attention, "observations"),
                text(attention, "referral"), followUp(attention.get("followUp")));
    }

    /** The {@code data} object of a 200 answer, retrying what a retry can fix. */
    private JsonNode get(String path, String correlationId) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(settings.requestTimeout())
                .header("Accept", "application/json")
                .header("X-Correlation-Id", correlationId)
                .GET();
        boolean renewed = false;
        SummaryUnavailableException last = null;
        for (int attempt = 1; attempt <= settings.maxAttempts(); attempt++) {
            if (attempt > 1) {
                pause(attempt - 1);
            }
            HttpResponse<byte[]> response;
            try {
                String token = tokens.token();
                response = send(request, token);
                if (response.statusCode() == 401 && !token.isEmpty() && !renewed) {
                    renewed = true;
                    tokens.invalidate(token);
                    response = send(request, tokens.token());
                }
            } catch (ServiceTokenException e) {
                last = new SummaryUnavailableException("no service token: " + e.getMessage(), e);
                if (!e.retryable()) {
                    throw last;
                }
                continue;
            } catch (IOException e) {
                last = new SummaryUnavailableException("the consultation service could not be reached", e);
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SummaryUnavailableException("interrupted while reading the summary", e);
            }
            int status = response.statusCode();
            if (status == 200) {
                return data(response.body());
            }
            last = new SummaryUnavailableException("the consultation service answered " + status, null);
            if (status != 429 && status < 500) {
                throw last;
            }
        }
        throw last;
    }

    private HttpResponse<byte[]> send(HttpRequest.Builder request, String token)
            throws IOException, InterruptedException {
        HttpRequest.Builder call = request.copy();
        if (!token.isEmpty()) {
            call.setHeader("Authorization", "Bearer " + token);
        }
        return http.send(call.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    /** Full jitter: a random wait between zero and the capped exponential backoff. */
    private void pause(int retry) {
        long base = settings.backoffBase().toMillis();
        long ceiling = Math.min(settings.backoffCap().toMillis(), base << Math.min(retry - 1, 30));
        try {
            sleeper.sleep(Duration.ofMillis((long) (random.getAsDouble() * ceiling)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SummaryUnavailableException("interrupted while reading the summary", e);
        }
    }

    private static JsonNode data(byte[] body) {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (IOException e) {
            throw invalid("the answer is not JSON");
        }
        JsonNode data = root == null ? null : root.get("data");
        if (data == null || !data.isObject()) {
            throw invalid("the answer has no data object");
        }
        return data;
    }

    private static UUID uuid(JsonNode node, String field) {
        String value = required(text(node, field), field);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw invalid(field + " is not a UUID");
        }
    }

    /** An optional string field: absent or null is {@code null}; any other type is invalid. */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw invalid(field + " is not a string");
        }
        return value.asText();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid(field + " is missing");
        }
        return value;
    }

    private static List<Medication> medications(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw invalid("medications is not an array");
        }
        List<Medication> medications = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isObject()) {
                throw invalid("a medication is not an object");
            }
            medications.add(new Medication(required(text(item, "name"), "medication name"), text(item, "dosage"),
                    text(item, "frequency"), text(item, "duration")));
        }
        return medications;
    }

    /** An object in the contract, plain text in the current service: both are kept. */
    private static Map<String, String> followUp(JsonNode node) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        if (node.isTextual()) {
            return node.asText().isBlank() ? Map.of() : Map.of(FOLLOW_UP_TEXT, node.asText());
        }
        if (!node.isObject()) {
            throw invalid("followUp is neither an object nor text");
        }
        Map<String, String> followUp = new LinkedHashMap<>();
        node.properties().forEach(entry -> {
            JsonNode value = entry.getValue();
            if (!value.isNull()) {
                followUp.put(entry.getKey(), value.isTextual() ? value.asText() : value.toString());
            }
        });
        return followUp;
    }

    private static SummaryUnavailableException invalid(String reason) {
        return new SummaryUnavailableException("invalid consultation answer: " + reason, null);
    }
}
