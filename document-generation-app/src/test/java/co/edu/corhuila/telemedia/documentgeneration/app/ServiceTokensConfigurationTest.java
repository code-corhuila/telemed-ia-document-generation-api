package co.edu.corhuila.telemedia.documentgeneration.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;

import org.junit.jupiter.api.Test;

import co.edu.corhuila.telemedia.documentgeneration.adapter.out.identity.IdentityServiceTokens;
import co.edu.corhuila.telemedia.documentgeneration.adapter.out.identity.ServiceTokens;

/** The service token source the composition root picks from the configuration. */
class ServiceTokensConfigurationTest {

    private final DocumentGenerationConfiguration configuration = new DocumentGenerationConfiguration();

    @Test
    void withAClientSecretTokensComeFromTheIdentityService() {
        ServiceTokens tokens = configuration.serviceTokens("http://identity-api:8081", "document-generation",
                "a-secret-for-tests", 5000, 60, Clock.systemUTC());

        assertThat(tokens).isInstanceOf(IdentityServiceTokens.class);
    }

    @Test
    void withoutAClientSecretCallsGoWithoutAToken() {
        ServiceTokens tokens = configuration.serviceTokens("http://identity-api:8081", "document-generation", " ",
                5000, 60, Clock.systemUTC());

        assertThat(tokens).isSameAs(ServiceTokens.NONE);
    }
}
