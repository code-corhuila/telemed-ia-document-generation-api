package co.edu.corhuila.telemedia.documentgeneration.adapter.in.http;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import co.edu.corhuila.telemedia.documentgeneration.domain.model.BusinessRuleViolationException;
import co.edu.corhuila.telemedia.documentgeneration.domain.model.DocumentStatus;
import co.edu.corhuila.telemedia.documentgeneration.domain.model.InvalidStatusTransitionException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Anexo C: every error, including unknown routes and malformed JSON, uses the same envelope. */
class ErrorHandlerTest {

    private static final String TRACE = "e2e-saga-1";

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new ErrorHandler())
                .addFilters(new CorrelationFilter())
                .build();
    }

    private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header(CorrelationFilter.HEADER, TRACE))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(CorrelationFilter.HEADER, TRACE))
                .andExpect(jsonPath("$.traceId").value(TRACE))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void invalidBodyNamesEveryInvalidField() throws Exception {
        perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[*].field").value(containsInAnyOrder("summaryId", "name")));
    }

    @Test
    void malformedJsonIsAValidationError() throws Exception {
        perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    void malformedIdIsAValidationErrorOnThatField() throws Exception {
        perform(get("/probe/items/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("id"));
    }

    @Test
    void missingHeaderIsNamedInTheDetails() throws Exception {
        perform(post("/probe/header"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("Idempotency-Key"));
    }

    @Test
    void invalidStatusTransitionIs422() throws Exception {
        perform(get("/probe/transition"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INVALID_STATUS_TRANSITION"));
    }

    @Test
    void businessRuleViolationIs422WithTheRuleMessage() throws Exception {
        perform(get("/probe/rule"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.message").value("the summary is not valid"));
    }

    @Test
    void unexpectedFailureIs500WithANeutralMessage() throws Exception {
        perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("SELECT"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }

    @Test
    void unknownRouteIs404WithTheEnvelope() throws Exception {
        perform(get("/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void unsupportedMethodIs405WithTheEnvelope() throws Exception {
        perform(delete("/probe/rule"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error").value("METHOD_NOT_ALLOWED"));
    }

    @RestController
    static class ProbeController {

        record Body(@NotNull UUID summaryId, @NotBlank String name) {
        }

        @PostMapping("/probe/body")
        String body(@Valid @RequestBody Body body) {
            return "ok";
        }

        @GetMapping("/probe/items/{id}")
        String item(@PathVariable("id") UUID id) {
            return "ok";
        }

        @PostMapping("/probe/header")
        String header(@RequestHeader("Idempotency-Key") String key) {
            return "ok";
        }

        @GetMapping("/probe/transition")
        String transition() {
            throw new InvalidStatusTransitionException(DocumentStatus.AVAILABLE, DocumentStatus.GENERATING);
        }

        @GetMapping("/probe/rule")
        String rule() {
            throw new BusinessRuleViolationException("the summary is not valid");
        }

        @GetMapping("/probe/boom")
        String boom() {
            throw new IllegalStateException("SELECT * FROM document_generation.consultation_document");
        }
    }
}
