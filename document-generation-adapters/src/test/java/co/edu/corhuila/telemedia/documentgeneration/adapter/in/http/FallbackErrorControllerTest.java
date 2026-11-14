package co.edu.corhuila.telemedia.documentgeneration.adapter.in.http;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import jakarta.servlet.RequestDispatcher;

/** Errors raised outside the controllers (container, filters) also use the envelope. */
class FallbackErrorControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new FallbackErrorController()).build();
    }

    @Test
    void keepsTheStatusAndTheTraceOfTheFailedRequest() throws Exception {
        mockMvc.perform(get("/error")
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 401)
                        .requestAttr(CorrelationFilter.ATTRIBUTE, "trace-401"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(CorrelationFilter.HEADER, "trace-401"))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.traceId").value("trace-401"));
    }

    @Test
    void withoutAStatusItIsAnInternalError() throws Exception {
        mockMvc.perform(get("/error"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }
}
