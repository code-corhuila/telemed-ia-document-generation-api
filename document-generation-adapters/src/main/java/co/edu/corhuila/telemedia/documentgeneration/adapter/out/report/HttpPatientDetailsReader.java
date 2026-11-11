package co.edu.corhuila.telemedia.documentgeneration.adapter.out.report;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import co.edu.corhuila.telemedia.documentgeneration.adapter.out.identity.ServiceTokens;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.ConsultationSummary;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.PatientDetails;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.PatientDetailsReader;

/**
 * DEC-015: the names from identity ({@code GET /internal/account-names}, auth DEC-014) and the
 * reason for the visit from the agent ({@code GET /internal/preconsultation-reports}, agent
 * DEC-019), with the service token of this service and the correlation id.
 *
 * <p>Both are optional: one attempt each (a {@code 401} is answered once with a new token);
 * any other answer, a network error or a token that cannot be obtained leaves the field empty.
 * Logs carry the path and the status only, never names or the text.
 */
public final class HttpPatientDetailsReader implements PatientDetailsReader {

    private static final Logger LOG = LoggerFactory.getLogger(HttpPatientDetailsReader.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** @param agentBaseUrl {@code null} when the agent is not configured: no reason is read */
    public record Settings(URI identityBaseUrl, URI agentBaseUrl, Duration requestTimeout) {

        public Settings {
            Objects.requireNonNull(identityBaseUrl, "identityBaseUrl");
            Objects.requireNonNull(requestTimeout, "requestTimeout");
        }
    }

    private final HttpClient http;
    private final Settings settings;
    private final ServiceTokens tokens;

    public HttpPatientDetailsReader(HttpClient http, Settings settings, ServiceTokens tokens) {
        this.http = Objects.requireNonNull(http, "http");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
    }

    @Override
    public PatientDetails read(ConsultationSummary summary, String correlationId) {
        Map<UUID, String> names = names(summary, correlationId);
        return new PatientDetails(names.get(summary.patientId()),
                summary.professionalId() == null ? null : names.get(summary.professionalId()),
                reasonForVisit(summary.appointmentId(), correlationId));
    }

    private Map<UUID, String> names(ConsultationSummary summary, String correlationId) {
        Set<UUID> ids = new LinkedHashSet<>();
        ids.add(summary.patientId());
        if (summary.professionalId() != null) {
            ids.add(summary.professionalId());
        }
        String query = ids.stream().map(UUID::toString).collect(Collectors.joining(","));
        Map<UUID, String> names = new HashMap<>();
        data(settings.identityBaseUrl(), "/internal/account-names?ids=" + encode(query), correlationId)
                .filter(JsonNode::isArray)
                .ifPresent(accounts -> accounts.forEach(account -> {
                    String id = account.path("id").asText("");
                    String fullName = account.path("fullName").asText("");
                    try {
                        if (!fullName.isBlank()) {
                            names.put(UUID.fromString(id), fullName);
                        }
                    } catch (IllegalArgumentException notAnId) {
                        // Left out: only the ids asked for are shown.
                    }
                }));
        return names;
    }

    private String reasonForVisit(UUID appointmentId, String correlationId) {
        if (settings.agentBaseUrl() == null) {
            return null;
        }
        return data(settings.agentBaseUrl(), "/internal/preconsultation-reports?appointmentId=" + appointmentId,
                correlationId)
                .map(report -> report.path("reasonForVisit").asText(""))
                .filter(text -> !text.isBlank())
                .orElse(null);
    }

    /** The {@code data} of a 200 answer, or empty for anything else. */
    private Optional<JsonNode> data(URI base, String pathAndQuery, String correlationId) {
        String root = base.toString().endsWith("/") ? base.toString().substring(0, base.toString().length() - 1)
                : base.toString();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(root + pathAndQuery))
                .timeout(settings.requestTimeout())
                .header("Accept", "application/json")
                .header("X-Correlation-Id", correlationId)
                .GET();
        try {
            String token = tokens.token();
            HttpResponse<byte[]> response = send(request, token);
            if (response.statusCode() == 401 && !token.isEmpty()) {
                tokens.invalidate(token);
                response = send(request, tokens.token());
            }
            if (response.statusCode() != 200) {
                LOG.warn("optional PDF detail {} answered {}", pathAndQuery.substring(0, pathAndQuery.indexOf('?')),
                        response.statusCode());
                return Optional.empty();
            }
            JsonNode data = JSON.readTree(response.body()).get("data");
            return Optional.ofNullable(data);
        } catch (IOException | RuntimeException e) {
            LOG.warn("optional PDF detail {} could not be read: {}",
                    pathAndQuery.substring(0, pathAndQuery.indexOf('?')), e.getClass().getSimpleName());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private HttpResponse<byte[]> send(HttpRequest.Builder request, String token)
            throws IOException, InterruptedException {
        HttpRequest.Builder call = request.copy();
        if (!token.isEmpty()) {
            call.setHeader("Authorization", "Bearer " + token);
        }
        return http.send(call.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("%2C", ",");
    }
}
