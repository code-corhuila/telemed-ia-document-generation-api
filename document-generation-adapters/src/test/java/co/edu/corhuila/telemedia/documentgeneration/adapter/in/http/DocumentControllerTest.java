package co.edu.corhuila.telemedia.documentgeneration.adapter.in.http;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;

import co.edu.corhuila.telemedia.documentgeneration.application.port.in.Page;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.PageRequest;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.ConsultationDocumentRepository;
import co.edu.corhuila.telemedia.documentgeneration.application.usecase.DocumentQueryService;
import co.edu.corhuila.telemedia.documentgeneration.domain.model.ConsultationDocument;
import co.edu.corhuila.telemedia.documentgeneration.domain.model.DocumentStatus;

/**
 * GET / and GET /{id} of document-service.yaml, through the whole HTTP chain
 * (correlation, token, controller, use case, error envelope) with an in-memory
 * repository (Anexo C, "HTTP" tests).
 */
class DocumentControllerTest {

    private static final String BASE = "/api/documents";
    private static final UUID ANA = UUID.fromString("c1b2c3d4-3333-4333-8333-333333333333");
    private static final UUID LUIS = UUID.fromString("d1b2c3d4-4444-4444-8444-444444444444");
    private static final Instant T0 = Instant.parse("2026-10-21T15:00:00Z");

    private final List<ConsultationDocument> stored = new ArrayList<>();
    private MockMvc mockMvc;

    private ConsultationDocument anaPending;
    private ConsultationDocument anaAvailable;
    private ConsultationDocument luisDoc;

    @BeforeEach
    void setUp() {
        anaPending = ConsultationDocument.request(UUID.randomUUID(), UUID.randomUUID(), ANA, T0);
        anaAvailable = ConsultationDocument.request(UUID.randomUUID(), UUID.randomUUID(), ANA, T0.plusSeconds(60))
                .startGeneration()
                .markAvailable("patients/ana/doc.pdf", T0.plusSeconds(90));
        luisDoc = ConsultationDocument.request(UUID.randomUUID(), UUID.randomUUID(), LUIS, T0.plusSeconds(120));
        stored.addAll(List.of(anaPending, anaAvailable, luisDoc));

        Rs256Verifier verifier = new Rs256Verifier(TestTokens.trustedPublicKey(), Clock.systemUTC());
        mockMvc = MockMvcBuilders
                .standaloneSetup(new DocumentController(new DocumentQueryService(new ListRepository())))
                .setControllerAdvice(new ErrorHandler())
                .addFilters(new CorrelationFilter(), new AuthFilter(verifier, new ObjectMapper()))
                .build();
    }

    private static String bearer(String subject, String role) {
        long exp = Instant.now().getEpochSecond() + 3600;
        return "Bearer " + TestTokens.rs256(
                "{\"sub\":\"" + subject + "\",\"roles\":[\"" + role + "\"],\"exp\":" + exp + "}");
    }

    private ResultActions as(UUID patient, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer(patient.toString(), "PATIENT")));
    }

    @Nested
    class ListDocuments {

        @Test
        void aPatientGetsOnlyTheirDocumentsMostRecentFirstWithMeta() throws Exception {
            as(ANA, get(BASE))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.data.length()").value(2))
                    .andExpect(jsonPath("$.data[0].id").value(anaAvailable.id().toString()))
                    .andExpect(jsonPath("$.data[1].id").value(anaPending.id().toString()))
                    .andExpect(jsonPath("$.meta.page").value(1))
                    .andExpect(jsonPath("$.meta.limit").value(20))
                    .andExpect(jsonPath("$.meta.total").value(2))
                    .andExpect(jsonPath("$.meta.totalPages").value(1));
        }

        @Test
        void theRepresentationFollowsTheContract() throws Exception {
            as(ANA, get(BASE))
                    .andExpect(jsonPath("$.data[0].summaryId").value(anaAvailable.summaryId().toString()))
                    .andExpect(jsonPath("$.data[0].patientId").value(ANA.toString()))
                    .andExpect(jsonPath("$.data[0].format").value("PDF"))
                    .andExpect(jsonPath("$.data[0].status").value("AVAILABLE"))
                    .andExpect(jsonPath("$.data[0].retryCount").value(0))
                    .andExpect(jsonPath("$.data[0].createdAt").value("2026-10-21T15:01:00Z"))
                    .andExpect(jsonPath("$.data[0].generatedAt").value("2026-10-21T15:01:30Z"))
                    .andExpect(jsonPath("$.data[0].storageReference").doesNotExist())
                    .andExpect(jsonPath("$.data[0].errorMessage").doesNotExist())
                    .andExpect(jsonPath("$.data[1].generatedAt").doesNotExist());
        }

        @Test
        void pageLimitAndStatusAreApplied() throws Exception {
            as(ANA, get(BASE).param("page", "2").param("limit", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(anaPending.id().toString()))
                    .andExpect(jsonPath("$.meta.page").value(2))
                    .andExpect(jsonPath("$.meta.limit").value(1))
                    .andExpect(jsonPath("$.meta.totalPages").value(2));
            as(ANA, get(BASE).param("status", "PENDING"))
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].status").value("PENDING"));
        }

        @Test
        void theRouteAlsoAnswersWithATrailingSlash() throws Exception {
            as(ANA, get(BASE + "/")).andExpect(status().isOk());
        }

        @Test
        void outOfRangeOrUnknownParametersAre400NamingEachField() throws Exception {
            as(ANA, get(BASE).param("page", "0").param("limit", "101").param("status", "DONE"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.details[*].field").value(containsInAnyOrder("page", "limit", "status")));
            as(ANA, get(BASE).param("limit", "0"))
                    .andExpect(status().isBadRequest());
            as(ANA, get(BASE).param("limit", "abc"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.details[0].field").value("limit"));
        }

        @Test
        void aNonPatientIs403() throws Exception {
            mockMvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, bearer(ANA.toString(), "ADMIN")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("FORBIDDEN"))
                    .andExpect(jsonPath("$.traceId").isNotEmpty());
        }

        @Test
        void withoutTokenItIs401() throws Exception {
            mockMvc.perform(get(BASE))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
        }
    }

    @Nested
    class GetById {

        @Test
        void theOwnerGetsTheDocument() throws Exception {
            as(ANA, get(BASE + "/" + anaPending.id()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(anaPending.id().toString()))
                    .andExpect(jsonPath("$.status").value("PENDING"))
                    .andExpect(jsonPath("$.createdAt").value("2026-10-21T15:00:00Z"));
        }

        @Test
        void anotherPatientsDocumentIs403NotFound404() throws Exception {
            as(ANA, get(BASE + "/" + luisDoc.id()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("FORBIDDEN"));
        }

        @Test
        void anUnknownDocumentIs404() throws Exception {
            as(ANA, get(BASE + "/" + UUID.randomUUID()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("NOT_FOUND"));
        }

        @Test
        void aMalformedIdIs400() throws Exception {
            as(ANA, get(BASE + "/not-a-uuid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.details[0].field").value("id"));
        }
    }

    /** In-memory fake of the output port, ordered like the SQL adapter. */
    private final class ListRepository implements ConsultationDocumentRepository {

        @Override
        public Optional<ConsultationDocument> findById(UUID id) {
            return stored.stream().filter(document -> document.id().equals(id)).findFirst();
        }

        @Override
        public Page<ConsultationDocument> findByPatient(UUID patientId, Optional<DocumentStatus> status,
                PageRequest page) {
            List<ConsultationDocument> matching = stored.stream()
                    .filter(document -> document.isOwnedBy(patientId))
                    .filter(document -> status.map(document.status()::equals).orElse(true))
                    .sorted(Comparator.comparing(ConsultationDocument::createdAt).reversed())
                    .toList();
            return Page.of(matching.stream().skip(page.offset()).limit(page.limit()).toList(),
                    page, matching.size());
        }
    }
}
