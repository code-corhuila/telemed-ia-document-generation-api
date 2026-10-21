package co.edu.corhuila.telemedia.documentgeneration.app;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;

import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Test-only key pair: the running service receives the public key through
 * {@code JWT_PUBLIC_KEY}, as in every environment; only the test signs tokens.
 */
final class TestJwt {

    private static final KeyPair KEYS = newKeyPair();
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    private TestJwt() {
    }

    static void register(DynamicPropertyRegistry registry) {
        registry.add("JWT_PUBLIC_KEY", TestJwt::publicKeyPemOneLine);
    }

    static String bearer(String subject, String role) {
        long exp = Instant.now().getEpochSecond() + 3600;
        String header = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}";
        String payload = "{\"sub\":\"" + subject + "\",\"roles\":[\"" + role + "\"],\"exp\":" + exp + "}";
        String signingInput = encode(header) + "." + encode(payload);
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(KEYS.getPrivate());
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return "Bearer " + signingInput + "." + B64URL.encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String publicKeyPemOneLine() {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(KEYS.getPublic().getEncoded());
        return ("-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----").replace("\n", "\\n");
    }

    private static String encode(String json) {
        return B64URL.encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static KeyPair newKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
