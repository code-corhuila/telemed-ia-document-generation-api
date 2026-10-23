package co.edu.corhuila.telemedia.documentgeneration.adapter.out.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import co.edu.corhuila.telemedia.documentgeneration.adapter.out.identity.IdentityServiceTokens.Settings;

/**
 * Against a stub of {@code POST /internal/service-token} of the identity service
 * (identity-and-access-api #117): client credentials in, a 10-minute RS256 token out,
 * {@code 401 INVALID_CLIENT} for any wrong id or secret.
 */
class IdentityServiceTokensTest {

    private static final String SECRET = "client-secret-for-tests-0001";
    private static final Instant T0 = Instant.parse("2026-10-23T15:00:00Z");

    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final AtomicInteger issued = new AtomicInteger();
    private volatile int status = 200;
    private volatile String answer;
    private final MutableClock clock = new MutableClock(T0);

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " "
                    + exchange.getRequestHeaders().getFirst("Content-Type"));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String body = answer != null ? answer : """
                    {"accessToken": "token-%d", "tokenType": "Bearer", "expiresAt": "%s"}
                    """.formatted(issued.incrementAndGet(), clock.instant().plus(Duration.ofMinutes(10)));
            if (status != 200) {
                body = "{\"error\":\"INVALID_CLIENT\",\"message\":\"invalid client\"}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private Settings settings(URI identity) {
        return new Settings(identity, "document-generation", SECRET, Duration.ofMillis(500), Duration.ofMinutes(1));
    }

    private IdentityServiceTokens tokens() {
        return new IdentityServiceTokens(HttpClient.newHttpClient(),
                settings(URI.create("http://127.0.0.1:" + server.getAddress().getPort())), clock);
    }

    @Test
    void theTokenIsRequestedWithTheClientCredentials() throws IOException {
        assertThat(tokens().token()).isEqualTo("token-1");

        assertThat(paths).containsExactly("POST /internal/service-token application/json");
        JsonNode body = new ObjectMapper().readTree(bodies.get(0));
        assertThat(body.get("clientId").asText()).isEqualTo("document-generation");
        assertThat(body.get("clientSecret").asText()).isEqualTo(SECRET);
    }

    @Test
    void theTokenIsKeptUntilAMinuteBeforeItExpires() {
        IdentityServiceTokens tokens = tokens();
        tokens.token();

        clock.advance(Duration.ofMinutes(8).plusSeconds(59));
        assertThat(tokens.token()).isEqualTo("token-1");

        clock.advance(Duration.ofSeconds(1));
        assertThat(tokens.token()).isEqualTo("token-2");
        assertThat(paths).hasSize(2);
    }

    @Test
    void aRejectedTokenIsRenewedOnce() {
        IdentityServiceTokens tokens = tokens();
        String rejected = tokens.token();

        tokens.invalidate(rejected);

        assertThat(tokens.token()).isEqualTo("token-2");
    }

    @Test
    void invalidatingAnOlderTokenKeepsTheCurrentOne() {
        IdentityServiceTokens tokens = tokens();
        String old = tokens.token();
        tokens.invalidate(old);
        String current = tokens.token();

        tokens.invalidate(old);

        assertThat(tokens.token()).isEqualTo(current);
        assertThat(paths).hasSize(2);
    }

    @Test
    void wrongCredentialsAreNotRetryableAndNeverEchoTheSecret() {
        status = 401;

        assertThatThrownBy(() -> tokens().token())
                .isInstanceOfSatisfying(ServiceTokenException.class, e -> assertThat(e.retryable()).isFalse())
                .hasMessageContaining("401")
                .hasMessageNotContaining(SECRET);
    }

    @Test
    void anIdentityServerErrorIsRetryable() {
        status = 503;

        assertThatThrownBy(() -> tokens().token())
                .isInstanceOfSatisfying(ServiceTokenException.class, e -> assertThat(e.retryable()).isTrue());
    }

    @Test
    void anUnreachableIdentityServiceIsRetryable() {
        IdentityServiceTokens unreachable = new IdentityServiceTokens(HttpClient.newHttpClient(),
                settings(URI.create("http://127.0.0.1:1")), clock);

        assertThatThrownBy(unreachable::token)
                .isInstanceOfSatisfying(ServiceTokenException.class, e -> assertThat(e.retryable()).isTrue());
    }

    @Test
    void anAnswerOutsideTheContractIsRejected() {
        for (String invalid : List.of("not json", "{\"tokenType\":\"Bearer\",\"expiresAt\":\"2026-10-23T15:10:00Z\"}",
                "{\"accessToken\":\"t\",\"tokenType\":\"Basic\",\"expiresAt\":\"2026-10-23T15:10:00Z\"}",
                "{\"accessToken\":\"t\",\"tokenType\":\"Bearer\",\"expiresAt\":\"tomorrow\"}")) {
            answer = invalid;

            assertThatThrownBy(() -> tokens().token()).as(invalid).isInstanceOf(ServiceTokenException.class);
        }
    }

    @Test
    void theSecretNeverAppearsInTheSettings() {
        Settings settings = settings(URI.create("http://identity-api:8081"));

        assertThat(settings.toString()).doesNotContain(SECRET).contains("document-generation");
    }

    @Test
    void theSettingsAreValidated() {
        Duration second = Duration.ofSeconds(1);
        URI identity = URI.create("http://identity-api:8081");
        assertThatThrownBy(() -> new Settings(URI.create("/relative"), "id", SECRET, second, second))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Settings(identity, " ", SECRET, second, second))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Settings(identity, "id", " ", second, second))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Settings(identity, "id", SECRET, Duration.ZERO, second))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Settings(identity, "id", SECRET, second, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withoutCredentialsNoTokenIsSent() {
        assertThat(ServiceTokens.NONE.token()).isEmpty();
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
