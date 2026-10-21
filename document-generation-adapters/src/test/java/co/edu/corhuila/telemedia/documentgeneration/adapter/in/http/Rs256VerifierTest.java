package co.edu.corhuila.telemedia.documentgeneration.adapter.in.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import co.edu.corhuila.telemedia.documentgeneration.adapter.in.http.Rs256Verifier.InvalidTokenException;

/** Numeral 5.3.7: each service validates the JWT itself, with a closed list of algorithms. */
class Rs256VerifierTest {

    private static final long NOW = 1_800_000_000L;
    private static final String SUBJECT = "c1b2c3d4-3333-4333-8333-333333333333";

    private final Rs256Verifier verifier = new Rs256Verifier(TestTokens.trustedPublicKey(),
            Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC));

    private static String payload(long exp) {
        return "{\"sub\":\"" + SUBJECT + "\",\"roles\":[\"PATIENT\"],\"iat\":" + (NOW - 60) + ",\"exp\":" + exp + "}";
    }

    @Test
    void aValidTokenGivesItsSubjectAndRoles() {
        AuthenticatedUser user = verifier.verify(TestTokens.rs256(payload(NOW + 3600)));

        assertThat(user.subject()).isEqualTo(SUBJECT);
        assertThat(user.roles()).containsExactly("PATIENT");
        assertThat(user.hasRole("PATIENT")).isTrue();
        assertThat(user.hasRole("ADMIN")).isFalse();
    }

    @Test
    void rolesAreOptional() {
        AuthenticatedUser user = verifier.verify(TestTokens.rs256("{\"sub\":\"u-1\",\"exp\":" + (NOW + 60) + "}"));

        assertThat(user.roles()).isEmpty();
    }

    @Test
    void algNoneIsRejected() {
        assertRejected(TestTokens.unsigned(payload(NOW + 3600)));
    }

    @Test
    void hs256SignedWithThePublicKeyIsRejected() {
        assertRejected(TestTokens.hs256WithPublicKeyAsSecret(payload(NOW + 3600)));
    }

    @Test
    void anyOtherAlgorithmIsRejectedEvenWithAValidRsaSignature() {
        assertRejected(TestTokens.signedWith(TestTokens.TRUSTED.getPrivate(),
                "{\"alg\":\"RS512\",\"typ\":\"JWT\"}", payload(NOW + 3600)));
        assertRejected(TestTokens.signedWith(TestTokens.TRUSTED.getPrivate(),
                "{\"alg\":\"rs256\",\"typ\":\"JWT\"}", payload(NOW + 3600)));
        assertRejected(TestTokens.signedWith(TestTokens.TRUSTED.getPrivate(),
                "{\"typ\":\"JWT\"}", payload(NOW + 3600)));
    }

    @Test
    void aTokenSignedWithAnotherKeyIsRejected() {
        assertRejected(TestTokens.signedWith(TestTokens.OTHER.getPrivate(),
                "{\"alg\":\"RS256\",\"typ\":\"JWT\"}", payload(NOW + 3600)));
    }

    @Test
    void aTamperedPayloadIsRejected() {
        String[] parts = TestTokens.rs256(payload(NOW + 3600)).split("\\.");
        String forged = parts[0] + "." + TestTokens.encode(payload(NOW + 3600).replace("PATIENT", "ADMIN"))
                + "." + parts[2];

        assertRejected(forged);
    }

    @Test
    void anExpiredTokenIsRejectedBeyondThirtySecondsOfSkew() {
        assertRejected(TestTokens.rs256(payload(NOW - 31)));
        assertThat(verifier.verify(TestTokens.rs256(payload(NOW - 25))).subject()).isEqualTo(SUBJECT);
    }

    @Test
    void expAndSubAreRequired() {
        assertRejected(TestTokens.rs256("{\"sub\":\"" + SUBJECT + "\"}"));
        assertRejected(TestTokens.rs256("{\"sub\":\"" + SUBJECT + "\",\"exp\":\"tomorrow\"}"));
        assertRejected(TestTokens.rs256("{\"exp\":" + (NOW + 60) + "}"));
        assertRejected(TestTokens.rs256("{\"sub\":\" \",\"exp\":" + (NOW + 60) + "}"));
    }

    @Test
    void aTokenNotYetValidIsRejected() {
        assertRejected(TestTokens.rs256("{\"sub\":\"u-1\",\"nbf\":" + (NOW + 120) + ",\"exp\":" + (NOW + 3600) + "}"));
    }

    @Test
    void malformedTokensAreRejected() {
        assertRejected(null);
        assertRejected("");
        assertRejected("only.two");
        assertRejected("a.b.c.d");
        assertRejected("!!!.###.$$$");
        assertRejected(TestTokens.encode("not json") + "." + TestTokens.encode("{}") + ".c2ln");
    }

    @Test
    void thePublicKeyIsReadFromAOneLinePem() {
        assertThat(Rs256Verifier.parsePublicKey(TestTokens.trustedPublicKeyPemOneLine()))
                .isEqualTo(TestTokens.trustedPublicKey());
    }

    @Test
    void anInvalidPemIsAConfigurationError() {
        assertThatThrownBy(() -> Rs256Verifier.parsePublicKey("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Rs256Verifier.parsePublicKey("-----BEGIN PUBLIC KEY-----\\nnot-a-key\\n"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void assertRejected(String token) {
        assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(InvalidTokenException.class);
    }
}
