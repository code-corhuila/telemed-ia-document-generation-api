package co.edu.corhuila.telemedia.documentgeneration.adapter.out.identity;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Service token from the identity service (identity-and-access-api #117): client
 * credentials at {@code POST /internal/service-token}, reachable only inside the platform
 * network. The token lasts about ten minutes and has no refresh token, so it is kept in
 * memory and requested again {@code refreshMargin} before it expires, or when another
 * service rejects it.
 *
 * <p>Neither the secret nor a token is ever logged or put in an exception message.
 */
public final class IdentityServiceTokens implements ServiceTokens {

    static final String PATH = "/internal/service-token";

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * @param clientSecret from the environment, never versioned; left out of {@link #toString()}
     * @param refreshMargin how long before its expiry a token is replaced
     */
    public record Settings(URI identityUrl, String clientId, String clientSecret, Duration requestTimeout,
            Duration refreshMargin) {

        public Settings {
            Objects.requireNonNull(identityUrl, "identityUrl");
            Objects.requireNonNull(requestTimeout, "requestTimeout");
            Objects.requireNonNull(refreshMargin, "refreshMargin");
            if (!"http".equals(identityUrl.getScheme()) && !"https".equals(identityUrl.getScheme())) {
                throw new IllegalArgumentException("the identity URL must be absolute http(s)");
            }
            if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
                throw new IllegalArgumentException("the client id and secret are required");
            }
            if (!requestTimeout.isPositive() || refreshMargin.isNegative()) {
                throw new IllegalArgumentException("the timeout must be positive and the margin not negative");
            }
        }

        @Override
        public String toString() {
            return "Settings[identityUrl=" + identityUrl + ", clientId=" + clientId + ", clientSecret=***"
                    + ", requestTimeout=" + requestTimeout + ", refreshMargin=" + refreshMargin + "]";
        }
    }

    private record Issued(String value, Instant expiresAt) { }

    private final HttpClient http;
    private final Settings settings;
    private final Clock clock;
    private final URI endpoint;
    private Issued current;

    public IdentityServiceTokens(HttpClient http, Settings settings, Clock clock) {
        this.http = Objects.requireNonNull(http, "http");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
        String base = settings.identityUrl().toString();
        this.endpoint = URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + PATH);
    }

    @Override
    public synchronized String token() {
        if (current == null || !clock.instant().isBefore(current.expiresAt().minus(settings.refreshMargin()))) {
            current = request();
        }
        return current.value();
    }

    @Override
    public synchronized void invalidate(String rejected) {
        if (current != null && current.value().equals(rejected)) {
            current = null;
        }
    }

    private Issued request() {
        HttpResponse<byte[]> response;
        try {
            response = http.send(HttpRequest.newBuilder(endpoint)
                    .timeout(settings.requestTimeout())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(credentials()))
                    .build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new ServiceTokenException("the identity service could not be reached", true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceTokenException("interrupted while requesting the service token", false, e);
        }
        int status = response.statusCode();
        if (status != 200) {
            throw new ServiceTokenException("the identity service answered " + status, status == 429 || status >= 500,
                    null);
        }
        return issued(response.body());
    }

    private String credentials() {
        try {
            return JSON.writeValueAsString(Map.of("clientId", settings.clientId(),
                    "clientSecret", settings.clientSecret()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("the credentials could not be written", e);
        }
    }

    private static Issued issued(byte[] body) {
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode token = root == null ? null : root.get("accessToken");
            JsonNode type = root == null ? null : root.get("tokenType");
            JsonNode expiresAt = root == null ? null : root.get("expiresAt");
            if (token == null || !token.isTextual() || token.asText().isBlank()
                    || type == null || !"Bearer".equalsIgnoreCase(type.asText())
                    || expiresAt == null || !expiresAt.isTextual()) {
                throw invalid();
            }
            return new Issued(token.asText(), Instant.parse(expiresAt.asText()));
        } catch (IOException | DateTimeParseException e) {
            throw invalid();
        }
    }

    private static ServiceTokenException invalid() {
        return new ServiceTokenException("invalid identity answer", false, null);
    }
}
