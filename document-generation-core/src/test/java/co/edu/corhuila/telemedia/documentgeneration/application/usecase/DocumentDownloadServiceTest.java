package co.edu.corhuila.telemedia.documentgeneration.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import co.edu.corhuila.telemedia.documentgeneration.application.port.in.Caller;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DocumentAccessDeniedException;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DownloadLink;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.DocumentNotFoundException;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.DocumentStorage;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.StorageUnavailableException;
import co.edu.corhuila.telemedia.documentgeneration.domain.model.BusinessRuleViolationException;
import co.edu.corhuila.telemedia.documentgeneration.domain.model.ConsultationDocument;

/**
 * GET /{id}/download (ADR-015): ownership is validated before any URL is issued, and only
 * an AVAILABLE document has an object to download.
 */
class DocumentDownloadServiceTest {

    private static final UUID ANA = UUID.fromString("c1b2c3d4-3333-4333-8333-333333333333");
    private static final UUID LUIS = UUID.fromString("d1b2c3d4-4444-4444-8444-444444444444");
    private static final Instant T0 = Instant.parse("2026-10-21T15:00:00Z");
    private static final String KEY = "patients/ana/consultations/s-1/doc.pdf";

    private final InMemoryConsultationDocumentRepository repository = new InMemoryConsultationDocumentRepository();
    private final RecordingStorage storage = new RecordingStorage();
    private final DocumentDownloadService service =
            new DocumentDownloadService(new DocumentQueryService(repository), storage);

    private ConsultationDocument anaAvailable;
    private ConsultationDocument anaPending;
    private ConsultationDocument luisAvailable;

    private static Caller patient(UUID id) {
        return new Caller(id.toString(), Set.of(Caller.PATIENT));
    }

    private static ConsultationDocument available(UUID patient, String key) {
        return ConsultationDocument.request(UUID.randomUUID(), UUID.randomUUID(), patient, T0)
                .startGeneration()
                .markAvailable(key, T0.plusSeconds(30));
    }

    @BeforeEach
    void seed() {
        anaAvailable = available(ANA, KEY);
        anaPending = ConsultationDocument.request(UUID.randomUUID(), UUID.randomUUID(), ANA, T0);
        luisAvailable = available(LUIS, "patients/luis/doc.pdf");
        repository.save(anaAvailable);
        repository.save(anaPending);
        repository.save(luisAvailable);
    }

    @Test
    void theOwnerGetsALinkForTheStoredObject() {
        DownloadLink link = service.requestDownload(patient(ANA), anaAvailable.id());

        assertThat(link).isEqualTo(storage.link);
        assertThat(storage.presignedKeys).containsExactly(KEY);
    }

    @Test
    void anotherPatientsDocumentIs403AndNoUrlIsIssued() {
        assertThatThrownBy(() -> service.requestDownload(patient(ANA), luisAvailable.id()))
                .isInstanceOf(DocumentAccessDeniedException.class);
        assertThat(storage.presignedKeys).isEmpty();
    }

    @Test
    void aCallerThatIsNotAPatientIs403() {
        Caller admin = new Caller(ANA.toString(), Set.of(Caller.ADMIN));

        assertThatThrownBy(() -> service.requestDownload(admin, anaAvailable.id()))
                .isInstanceOf(DocumentAccessDeniedException.class);
        assertThat(storage.presignedKeys).isEmpty();
    }

    @Test
    void anUnknownDocumentIs404() {
        assertThatThrownBy(() -> service.requestDownload(patient(ANA), UUID.randomUUID()))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void aDocumentThatIsNotAvailableIsABusinessRuleViolation() {
        assertThatThrownBy(() -> service.requestDownload(patient(ANA), anaPending.id()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("not available");
        assertThat(storage.presignedKeys).isEmpty();
    }

    @Test
    void anUnavailableStorageIsPropagated() {
        storage.failure = new StorageUnavailableException("storage down", new RuntimeException());

        assertThatThrownBy(() -> service.requestDownload(patient(ANA), anaAvailable.id()))
                .isInstanceOf(StorageUnavailableException.class);
    }

    /** Fake of the output port: records which object keys were presigned. */
    private static final class RecordingStorage implements DocumentStorage {

        private final List<String> presignedKeys = new ArrayList<>();
        private final DownloadLink link =
                new DownloadLink(URI.create("http://localhost:9000/telemed-documents/x?sig=1"), T0.plusSeconds(300));
        private RuntimeException failure;

        @Override
        public DownloadLink presignDownload(String objectKey) {
            presignedKeys.add(objectKey);
            if (failure != null) {
                throw failure;
            }
            return link;
        }

        @Override
        public void store(String objectKey, byte[] pdf) {
            throw new UnsupportedOperationException("downloads never store");
        }
    }
}
