package co.edu.corhuila.telemedia.documentgeneration.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import co.edu.corhuila.telemedia.documentgeneration.adapter.in.http.Rs256Verifier;

/**
 * Composition root for token validation (Anexo C). The identity service's public key
 * arrives as {@code JWT_PUBLIC_KEY} (one-line PEM with literal {@code \n}) or as the path
 * in {@code JWT_PUBLIC_KEY_FILE}. Without a valid key the service does not start.
 */
@Configuration
public class SecurityConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    Rs256Verifier rs256Verifier(@Value("${JWT_PUBLIC_KEY:}") String inlineKey,
            @Value("${JWT_PUBLIC_KEY_FILE:}") String keyFile, Clock clock) throws IOException {
        String pem = keyFile.isBlank() ? inlineKey : Files.readString(Path.of(keyFile));
        if (pem.isBlank()) {
            throw new IllegalStateException("JWT_PUBLIC_KEY or JWT_PUBLIC_KEY_FILE must be set");
        }
        return new Rs256Verifier(Rs256Verifier.parsePublicKey(pem), clock);
    }
}
