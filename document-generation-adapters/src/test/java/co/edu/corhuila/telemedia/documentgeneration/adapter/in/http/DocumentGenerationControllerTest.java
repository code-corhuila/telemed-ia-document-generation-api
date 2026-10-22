package co.edu.corhuila.telemedia.documentgeneration.adapter.in.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;

import co.edu.corhuila.telemedia.documentgeneration.application.port.in.Caller;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DocumentAccessDeniedException;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DocumentGeneration;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.GenerateDocumentCommand;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.GenerationResult;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.IdempotencyKeyReusedException;
import co.edu.corhuila.telemedia.documentgeneration.domain.model.ConsultationDocument;

/**
 * POST /generate of document-service.yaml through the whole HTTP chain (correlation,
 * token, controller, error envelope). The use case is a test double: its rules are
 * covered by DocumentGenerationServiceTest; here only the HTTP translation is checked.
 */
class DocumentGenerationControllerTest {

    private static final String ROUTE = "/api/documents/generate";
    private static final String KEY = "gen-key-0001";
    private static final String ADMIN = "a1b2c3d4-1111-4111-8111-111111111111";
    private static final UUID SUMMARY = UUID.fromString("5a1b2c3d-5555-4555-8555-555555555555");
    private static final UUID PATIENT = UUID.fromString("c1b2c3d4-3333-4333-8333-333333333333");
    private static final Instant T0 = Instant.parse("2026-10-21T15:00:00Z");

    private final ConsultationDocument document =
            ConsultationDocument.request(UUID.randomUUID(), SUMMARY, PATIENT, T0);
    private final RecordingGeneration generation = new RecordingGeneration();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        Rs256Verifier verifier = new Rs256Verifier(TestTokens.trustedPublicKey(), Clock.systemUTC());
        mockMvc = MockMvcBuilders
                .standaloneSetup(new DocumentGenerationController(generation))
                .setControllerAdvice(new ErrorHandler())
                .addFilters(new CorrelationFilter(), new AuthFilter(verifier, new ObjectMapper()))
                .build();
    }

    /** The token shape issued by the identity service: a single {@code role} string. */
    private static String bearer(String subject, String role) {
        long exp = Instant.now().getEpochSecond() + 3600;
        return "Bearer " + TestTokens.rs256("{\"iss\":\"telemed-ia-identity-and-access\",\"sub\":\"" + subject
                + "\",\"role\":\"" + role + "\",\"exp\":" + exp + "}");
    }

    private static String body(Object summaryId, Object patientId) {
        return "{\"summaryId\":\"" + summaryId + "\",\"patientId\":\"" + patientId + "\"}";
    }

    private ResultActions generate(String key, String json) throws Exception {
        var request = post(ROUTE).header(HttpHeaders.AUTHORIZATION, bearer(ADMIN, "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(json);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mockMvc.perform(request);
    }

    @Test
    void aNewRequestIs201WithLocationAndTheDocument() throws Exception {
        generation.result = new GenerationResult(document, true);

        generate(KEY, body(SUMMARY, PATIENT))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "/api/v1/documents/" + document.id()))
                .andExpect(jsonPath("$.id").value(document.id().toString()))
                .andExpect(jsonPath("$.summaryId").value(SUMMARY.toString()))
                .andExpect(jsonPath("$.patientId").value(PATIENT.toString()))
                .andExpect(jsonPath("$.format").value("PDF"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.retryCount").value(0))
                .andExpect(jsonPath("$.createdAt").value("2026-10-21T15:00:00Z"))
                .andExpect(jsonPath("$.storageReference").doesNotExist());
    }

    @Test
    void theUseCaseReceivesTheKeyTheBodyAndTheCallerFromTheToken() throws Exception {
        generation.result = new GenerationResult(document, true);

        generate(KEY, body(SUMMARY, PATIENT)).andExpect(status().isCreated());

        assertThat(generation.command).isEqualTo(new GenerateDocumentCommand(KEY, SUMMARY, PATIENT));
        assertThat(generation.caller.subject()).isEqualTo(ADMIN);
        assertThat(generation.caller.roles()).containsExactly("ADMIN");
    }

    @Test
    void anExistingDocumentIs200WithoutLocation() throws Exception {
        generation.result = new GenerationResult(document, false);

        generate(KEY, body(SUMMARY, PATIENT))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.id").value(document.id().toString()));
    }

    @Test
    void theSameKeyWithAnotherBodyIs422() throws Exception {
        generation.failure = new IdempotencyKeyReusedException();

        generate(KEY, body(SUMMARY, PATIENT))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void aCallerThatIsNotAdministratorIs403() throws Exception {
        generation.failure = new DocumentAccessDeniedException();

        mockMvc.perform(post(ROUTE).header(HttpHeaders.AUTHORIZATION, bearer(PATIENT.toString(), "PATIENT"))
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body(SUMMARY, PATIENT)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void withoutTokenItIs401AndTheUseCaseIsNotCalled() throws Exception {
        mockMvc.perform(post(ROUTE).header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body(SUMMARY, PATIENT)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));

        assertThat(generation.calls).isZero();
    }

    @Test
    void aMissingIdempotencyKeyIs400NamingTheHeader() throws Exception {
        generate(null, body(SUMMARY, PATIENT))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("Idempotency-Key"));

        assertThat(generation.calls).isZero();
    }

    @Test
    void anIdempotencyKeyOutside8To128CharactersIs400() throws Exception {
        generate("short-7", body(SUMMARY, PATIENT))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("Idempotency-Key"));
        generate("k".repeat(129), body(SUMMARY, PATIENT))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("Idempotency-Key"));

        assertThat(generation.calls).isZero();
    }

    @Test
    void theKeyLimitsThemselvesAreAccepted() throws Exception {
        generation.result = new GenerationResult(document, true);

        generate("k".repeat(8), body(SUMMARY, PATIENT)).andExpect(status().isCreated());
        generate("k".repeat(128), body(SUMMARY, PATIENT)).andExpect(status().isCreated());
    }

    @Test
    void missingOrMalformedIdsAre400NamingEachField() throws Exception {
        generate(KEY, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[*].field").value(containsInAnyOrder("summaryId", "patientId")));
        generate(KEY, body("not-a-uuid", "1-1-1-1-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[*].field").value(containsInAnyOrder("summaryId", "patientId")));
        generate(KEY, "{\"summaryId\":42,\"patientId\":null}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[*].field").value(containsInAnyOrder("summaryId", "patientId")));

        assertThat(generation.calls).isZero();
    }

    @Test
    void anUnknownPropertyIs400NamingIt() throws Exception {
        generate(KEY, "{\"summaryId\":\"" + SUMMARY + "\",\"patientId\":\"" + PATIENT + "\",\"format\":\"PDF\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("format"));

        assertThat(generation.calls).isZero();
    }

    @Test
    void aBodyThatIsNotAJsonObjectIs400() throws Exception {
        generate(KEY, "[]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
        generate(KEY, "{not json").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));

        assertThat(generation.calls).isZero();
    }

    @Test
    void headerAndBodyErrorsAreReportedTogether() throws Exception {
        generate(null, "{\"summaryId\":\"x\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[*].field")
                        .value(containsInAnyOrder("Idempotency-Key", "summaryId", "patientId")));
    }

    /** Records what the adapter passes to the input port and answers as configured. */
    private static final class RecordingGeneration implements DocumentGeneration {

        private GenerationResult result;
        private RuntimeException failure;
        private Caller caller;
        private GenerateDocumentCommand command;
        private int calls;

        @Override
        public GenerationResult request(Caller caller, GenerateDocumentCommand command) {
            calls++;
            this.caller = caller;
            this.command = command;
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }
}
