package co.edu.corhuila.telemedia.documentgeneration.adapter.in.http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.SignatureException;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Validates access tokens issued by the identity service (numeral 5.3.7, Anexo C).
 *
 * <ol>
 *   <li>Three dot-separated parts; the header is decoded first.</li>
 *   <li>{@code alg} must be exactly {@code RS256}: a closed list, so {@code none} or
 *       {@code HS256} signed with the public key as secret are rejected.</li>
 *   <li>RSA PKCS#1 v1.5 SHA-256 signature of {@code header.payload} with the public key.</li>
 *   <li>{@code exp} is required and not past (30 s of clock skew); {@code sub} is required.</li>
 * </ol>
 *
 * The service only holds the public key; it never signs tokens.
 */
public final class Rs256Verifier {

    public static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    private static final String ALGORITHM = "RS256";
    private static final Base64.Decoder BASE64_URL = Base64.getUrlDecoder();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RSAPublicKey publicKey;
    private final Clock clock;

    public Rs256Verifier(RSAPublicKey publicKey, Clock clock) {
        this.publicKey = Objects.requireNonNull(publicKey, "publicKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Reads a {@code PUBLIC KEY} PEM, in several lines or in one line with literal {@code \n}. */
    public static RSAPublicKey parsePublicKey(String pem) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalArgumentException("the JWT public key is empty");
        }
        String body = pem.replace("\\n", "\n")
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        try {
            byte[] der = Base64.getDecoder().decode(body);
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (IllegalArgumentException | ClassCastException | GeneralSecurityException e) {
            throw new IllegalArgumentException("the JWT public key is not a valid RSA PUBLIC KEY PEM", e);
        }
    }

    public AuthenticatedUser verify(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidTokenException("missing token");
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            throw new InvalidTokenException("the token must have three parts");
        }
        JsonNode header = decode(parts[0], "header");
        JsonNode alg = header.get("alg");
        if (alg == null || !alg.isTextual() || !ALGORITHM.equals(alg.asText())) {
            throw new InvalidTokenException("alg must be RS256");
        }
        if (!signatureMatches(parts)) {
            throw new InvalidTokenException("invalid signature");
        }
        JsonNode payload = decode(parts[1], "payload");
        Instant now = clock.instant();
        JsonNode exp = payload.get("exp");
        if (exp == null || !exp.isNumber()) {
            throw new InvalidTokenException("exp is required");
        }
        if (now.isAfter(Instant.ofEpochSecond(exp.asLong()).plus(CLOCK_SKEW))) {
            throw new InvalidTokenException("the token has expired");
        }
        JsonNode nbf = payload.get("nbf");
        if (nbf != null && nbf.isNumber() && now.plus(CLOCK_SKEW).isBefore(Instant.ofEpochSecond(nbf.asLong()))) {
            throw new InvalidTokenException("the token is not valid yet");
        }
        JsonNode sub = payload.get("sub");
        if (sub == null || !sub.isTextual() || sub.asText().isBlank()) {
            throw new InvalidTokenException("sub is required");
        }
        return new AuthenticatedUser(sub.asText(), roles(payload.get("roles")));
    }

    private boolean signatureMatches(String[] parts) {
        byte[] signature;
        try {
            signature = BASE64_URL.decode(parts[2]);
        } catch (IllegalArgumentException e) {
            return false;
        }
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey);
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            return verifier.verify(signature);
        } catch (SignatureException e) {
            return false;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("RSA signature verification is not available", e);
        }
    }

    private static JsonNode decode(String part, String name) {
        try {
            JsonNode node = JSON.readTree(BASE64_URL.decode(part));
            if (node == null || !node.isObject()) {
                throw new InvalidTokenException("the token " + name + " is not a JSON object");
            }
            return node;
        } catch (IOException | IllegalArgumentException e) {
            throw new InvalidTokenException("the token " + name + " is malformed");
        }
    }

    private static Set<String> roles(JsonNode node) {
        Set<String> roles = new HashSet<>();
        if (node != null && node.isArray()) {
            node.forEach(role -> {
                if (role.isTextual()) {
                    roles.add(role.asText());
                }
            });
        }
        return roles;
    }

    /** The token cannot be trusted; the reason is logged, never returned to the client. */
    public static final class InvalidTokenException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public InvalidTokenException(String reason) {
            super(reason);
        }
    }
}
