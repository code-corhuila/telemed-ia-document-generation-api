package co.edu.corhuila.telemedia.documentgeneration.adapter.in.http;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;

/** Anexo C "Autenticación" checks, at the HTTP boundary. */
class AuthFilterTest {

    private static final String TRACE = "auth-check-1";

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        Rs256Verifier verifier = new Rs256Verifier(TestTokens.trustedPublicKey(), Clock.systemUTC());
        mockMvc = MockMvcBuilders.standaloneSetup(new HealthController(), new WhoAmIController())
                .setControllerAdvice(new ErrorHandler())
                .addFilters(new CorrelationFilter(), new AuthFilter(verifier, new ObjectMapper()))
                .build();
    }

    private static String validToken() {
        long exp = Instant.now().getEpochSecond() + 3600;
        return TestTokens.rs256("{\"sub\":\"patient-1\",\"roles\":[\"PATIENT\"],\"exp\":" + exp + "}");
    }

    @Test
    void healthAnswersWithoutToken() throws Exception {
        mockMvc.perform(get("/health")).andExpect(status().isOk());
    }

    @Test
    void withoutTokenItIs401WithTheEnvelope() throws Exception {
        mockMvc.perform(get("/whoami").header(CorrelationFilter.HEADER, TRACE))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(header().string(CorrelationFilter.HEADER, TRACE))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.traceId").value(TRACE));
    }

    @Test
    void aNonBearerSchemeIs401() throws Exception {
        mockMvc.perform(get("/whoami").header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    void anInvalidTokenIs401() throws Exception {
        long exp = Instant.now().getEpochSecond() + 3600;
        String forged = TestTokens.unsigned("{\"sub\":\"patient-1\",\"exp\":" + exp + "}");

        mockMvc.perform(get("/whoami").header(HttpHeaders.AUTHORIZATION, "Bearer " + forged))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    void anExpiredTokenIs401() throws Exception {
        long exp = Instant.now().getEpochSecond() - 120;
        String expired = TestTokens.rs256("{\"sub\":\"patient-1\",\"exp\":" + exp + "}");

        mockMvc.perform(get("/whoami").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aValidTokenReachesTheControllerWithTheAuthenticatedUser() throws Exception {
        mockMvc.perform(get("/whoami").header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken()))
                .andExpect(status().isOk())
                .andExpect(content().string("patient-1 [PATIENT]"));
    }

    @RestController
    static class WhoAmIController {

        @GetMapping("/whoami")
        String whoAmI(HttpServletRequest request) {
            AuthenticatedUser user = AuthFilter.currentUser(request);
            return user.subject() + " " + user.roles();
        }
    }
}
