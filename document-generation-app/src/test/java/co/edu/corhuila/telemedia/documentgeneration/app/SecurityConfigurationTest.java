package co.edu.corhuila.telemedia.documentgeneration.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import co.edu.corhuila.telemedia.documentgeneration.adapter.in.http.Rs256Verifier;

/** The public key arrives by JWT_PUBLIC_KEY or JWT_PUBLIC_KEY_FILE; without it the service does not start. */
class SecurityConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SecurityConfiguration.class);

    @Test
    void withoutAPublicKeyTheServiceRefusesToStart() {
        runner.run(context -> assertThat(context).hasFailed());
    }

    @Test
    void readsTheOneLinePemFromJwtPublicKey() {
        runner.withPropertyValues("JWT_PUBLIC_KEY=" + TestJwt.publicKeyPemOneLine())
                .run(context -> assertThat(context).hasSingleBean(Rs256Verifier.class));
    }

    @Test
    void readsThePemFromJwtPublicKeyFile(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("jwt-public.pem");
        Files.writeString(file, TestJwt.publicKeyPemOneLine().replace("\\n", "\n"), StandardCharsets.US_ASCII);

        runner.withPropertyValues("JWT_PUBLIC_KEY_FILE=" + file)
                .run(context -> assertThat(context).hasSingleBean(Rs256Verifier.class));
    }

    @Test
    void anInvalidKeyIsAStartupError() {
        runner.withPropertyValues("JWT_PUBLIC_KEY=not-a-key")
                .run(context -> assertThat(context).hasFailed());
    }
}
