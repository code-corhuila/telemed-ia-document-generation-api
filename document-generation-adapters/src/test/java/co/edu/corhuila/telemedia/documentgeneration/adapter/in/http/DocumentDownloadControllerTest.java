package co.edu.corhuila.telemedia.documentgeneration.adapter.in.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;

import co.edu.corhuila.telemedia.documentgeneration.application.port.in.Caller;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DocumentAccessDeniedException;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DocumentDownloads;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DownloadLink;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.DocumentNotFoundException;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.StorageUnavailableException;
import co.edu.corhuila.telemedia.documentgeneration.domain.model.BusinessRuleViolationException;

/**
 * GET /{id}/download of document-service.yaml through the whole HTTP chain. The use case
 * is a test double: its rules are covered by DocumentDownloadServiceTest.
 */
class DocumentDownloadControllerTest {

    private static final UUID ANA = UUID.fromString("c1b2c3d4-3333-4333-8333-333333333333");
    private static final UUID DOCUMENT = UUID.fromString("6f178b1a-9c0d-4c23-96ad-1e8da3b2ef6d");
    private static final String URL = "http://localhost:9000/telemed-documents/patients/ana/doc.pdf"
            + "?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Expires=300&X-Amz-Signature=abc";

    private final RecordingDownloads downloads = new RecordingDownloads();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        Rs256Verifier verifier = new Rs256Verifier(TestTokens.trustedPublicKey(), Clock.systemUTC());
        mockMvc = MockMvcBuilders
                .standaloneSetup(new DocumentDownloadController(downloads))
                .setControllerAdvice(new ErrorHandler())
                .addFilters(new CorrelationFilter(), new AuthFilter(verifier, new ObjectMapper()))
                .build();
    }

    private static String bearer(String subject, String role) {
        long exp = Instant.now().getEpochSecond() + 3600;
        return "Bearer " + TestTokens.rs256(
                "{\"sub\":\"" + subject + "\",\"role\":\"" + role + "\",\"exp\":" + exp + "}");
    }

    private ResultActions download(String id) throws Exception {
        return mockMvc.perform(get("/api/documents/" + id + "/download")
                .header(HttpHeaders.AUTHORIZATION, bearer(ANA.toString(), "PATIENT")));
    }

    @Test
    void theOwnerGetsTheUrlAndItsExpiry() throws Exception {
        downloads.link = new DownloadLink(URI.create(URL), Instant.parse("2026-10-22T15:05:00Z"));

        download(DOCUMENT.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.downloadUrl").value(URL))
                .andExpect(jsonPath("$.expiresAt").value("2026-10-22T15:05:00Z"))
                .andExpect(jsonPath("$.storageReference").doesNotExist());

        assertThat(downloads.documentId).isEqualTo(DOCUMENT);
        assertThat(downloads.caller.subject()).isEqualTo(ANA.toString());
        assertThat(downloads.caller.roles()).containsExactly("PATIENT");
    }

    @Test
    void anotherPatientsDocumentIs403() throws Exception {
        downloads.failure = new DocumentAccessDeniedException();

        download(DOCUMENT.toString())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void anUnknownDocumentIs404() throws Exception {
        downloads.failure = new DocumentNotFoundException(DOCUMENT);

        download(DOCUMENT.toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void aDocumentThatIsNotAvailableIs422() throws Exception {
        downloads.failure = new BusinessRuleViolationException("the document is not available for download");

        download(DOCUMENT.toString())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("BUSINESS_RULE_VIOLATION"));
    }

    @Test
    void anUnavailableStorageIs503WithoutInternalDetail() throws Exception {
        downloads.failure = new StorageUnavailableException("minio:9000 refused", new RuntimeException());

        download(DOCUMENT.toString())
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("the service is temporarily unavailable"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void aMalformedIdIs400() throws Exception {
        download("not-a-uuid")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("id"));

        assertThat(downloads.calls).isZero();
    }

    @Test
    void withoutTokenItIs401() throws Exception {
        mockMvc.perform(get("/api/documents/" + DOCUMENT + "/download"))
                .andExpect(status().isUnauthorized());

        assertThat(downloads.calls).isZero();
    }

    /** Records what the adapter passes to the input port and answers as configured. */
    private static final class RecordingDownloads implements DocumentDownloads {

        private DownloadLink link;
        private RuntimeException failure;
        private Caller caller;
        private UUID documentId;
        private int calls;

        @Override
        public DownloadLink requestDownload(Caller caller, UUID documentId) {
            calls++;
            this.caller = caller;
            this.documentId = documentId;
            if (failure != null) {
                throw failure;
            }
            return link;
        }
    }
}
