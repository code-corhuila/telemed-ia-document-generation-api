package co.edu.corhuila.telemedia.documentgeneration.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import co.edu.corhuila.telemedia.documentgeneration.application.port.in.GenerationAttempt;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DownloadLink;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.ConsultationSummary;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.ConsultationSummaryReader;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.DocumentStatusStore;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.DocumentStorage;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.PdfGeneratedEvent;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.PdfRenderer;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.PdfRenderingException;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.StorageUnavailableException;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.SummaryUnavailableException;
import co.edu.corhuila.telemedia.documentgeneration.domain.model.ConsultationDocument;
import co.edu.corhuila.telemedia.documentgeneration.domain.model.DocumentStatus;

/**
 * DEC-005 and DEC-006: one generation attempt per call. PENDING|ERROR -> GENERATING,
 * read the summary, render, store, then AVAILABLE with its outbox event in one step;
 * any failure leaves ERROR with a neutral message, retried at most three times.
 */
class PdfGenerationServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-22T15:00:00Z");
    private static final UUID PATIENT = UUID.fromString("b0000000-0000-0000-0000-000000000001");
    private static final UUID SUMMARY = UUID.fromString("e0000000-0000-0000-0000-000000000102");
    private static final UUID EVENT = UUID.fromString("f0000000-0000-0000-0000-000000000001");
    private static final String TRACE = "trace-pdf-1";
    private static final byte[] PDF = {'%', 'P', 'D', 'F'};

    private final MutableClock clock = new MutableClock(T0);
    private final InMemoryConsultationDocumentRepository repository = new InMemoryConsultationDocumentRepository();
    private final RecordingStatusStore statusStore = new RecordingStatusStore();
    private final StubReader reader = new StubReader();
    private final StubRenderer renderer = new StubRenderer();
    private final RecordingStorage storage = new RecordingStorage();
    private final PdfGenerationService service = new PdfGenerationService(repository, statusStore, reader,
            renderer, storage, () -> EVENT, clock, Duration.ofSeconds(30));

    private ConsultationDocument pending() {
        ConsultationDocument document = ConsultationDocument.request(UUID.randomUUID(), SUMMARY, PATIENT, T0);
        repository.save(document);
        return document;
    }

    private ConsultationDocument failed(int retries) {
        ConsultationDocument document = ConsultationDocument.rehydrate(UUID.randomUUID(), SUMMARY, PATIENT,
                DocumentStatus.ERROR, retries, null, "PDF error", T0, null);
        repository.save(document);
        return document;
    }

    private static ConsultationSummary summary(UUID patientId) {
        return new ConsultationSummary(SUMMARY, UUID.randomUUID(), patientId, "Acute pharyngitis",
                "Rest and fluids", List.of(new ConsultationSummary.Medication("Acetaminophen", "500 mg",
                        "Every 8 hours", "3 days")),
                null, null, Map.of("inDays", "7"));
    }

    @Test
    void aPendingDocumentBecomesAvailableWithItsObjectAndOutboxEvent() {
        ConsultationDocument document = pending();
        reader.summary = summary(PATIENT);

        GenerationAttempt attempt = service.generate(document.id(), TRACE);

        String key = "patients/" + PATIENT + "/consultations/" + SUMMARY + "/" + document.id() + ".pdf";
        assertThat(attempt).isEqualTo(GenerationAttempt.AVAILABLE);
        assertThat(statusStore.statuses()).containsExactly(DocumentStatus.GENERATING, DocumentStatus.AVAILABLE);
        assertThat(storage.stored).containsOnlyKeys(key);
        assertThat(storage.stored.get(key)).isEqualTo(PDF);
        ConsultationDocument available = statusStore.last();
        assertThat(available.storageReference()).contains(key);
        assertThat(available.generatedAt()).contains(T0);
        assertThat(statusStore.event)
                .isEqualTo(new PdfGeneratedEvent(EVENT, document.id(), SUMMARY, PATIENT, T0, TRACE));
        assertThat(renderer.rendered).isEqualTo(reader.summary);
    }

    @Test
    void aRetryableErrorIsRetriedAndCountsTheRetry() {
        ConsultationDocument document = failed(1);
        reader.summary = summary(PATIENT);

        assertThat(service.generate(document.id(), TRACE)).isEqualTo(GenerationAttempt.AVAILABLE);
        assertThat(statusStore.transitions.get(0).to().retryCount()).isEqualTo(2);
    }

    @Test
    void aDocumentLeftGeneratingByARestartContinuesWithoutANewTransition() {
        ConsultationDocument generating = pending().startGeneration();
        repository.save(generating);
        reader.summary = summary(PATIENT);

        assertThat(service.generate(generating.id(), TRACE)).isEqualTo(GenerationAttempt.AVAILABLE);
        assertThat(statusStore.statuses()).containsExactly(DocumentStatus.AVAILABLE);
    }

    @Test
    void anUnreadableSummaryLeavesARetryableErrorAndStoresNothing() {
        ConsultationDocument document = pending();
        reader.failure = new SummaryUnavailableException("consultation-api 503", null);

        assertThat(service.generate(document.id(), TRACE)).isEqualTo(GenerationAttempt.RETRY);
        assertThat(statusStore.statuses()).containsExactly(DocumentStatus.GENERATING, DocumentStatus.ERROR);
        assertThat(statusStore.last().errorMessage()).contains("summary unavailable");
        assertThat(storage.stored).isEmpty();
        assertThat(statusStore.event).isNull();
    }

    @Test
    void aFailureOnTheLastRetryIsFinal() {
        ConsultationDocument document = failed(2);
        reader.failure = new SummaryUnavailableException("timeout", null);

        assertThat(service.generate(document.id(), TRACE)).isEqualTo(GenerationAttempt.FAILED);
        assertThat(statusStore.last().retryCount()).isEqualTo(3);
        assertThat(statusStore.last().canRetry()).isFalse();
    }

    @Test
    void aRenderingFailureIsAPdfError() {
        ConsultationDocument document = pending();
        reader.summary = summary(PATIENT);
        renderer.failure = new PdfRenderingException("font missing", null);

        assertThat(service.generate(document.id(), TRACE)).isEqualTo(GenerationAttempt.RETRY);
        assertThat(statusStore.last().errorMessage()).contains("PDF error");
        assertThat(storage.stored).isEmpty();
    }

    @Test
    void anUnavailableStorageLeavesAnError() {
        ConsultationDocument document = pending();
        reader.summary = summary(PATIENT);
        storage.failure = new StorageUnavailableException("rustfs down", null);

        assertThat(service.generate(document.id(), TRACE)).isEqualTo(GenerationAttempt.RETRY);
        assertThat(statusStore.last().errorMessage()).contains("storage unavailable");
        assertThat(statusStore.event).isNull();
    }

    @Test
    void aSummaryOfAnotherPatientIsNeverRendered() {
        ConsultationDocument document = pending();
        reader.summary = summary(UUID.fromString("b0000000-0000-0000-0000-000000000099"));

        assertThat(service.generate(document.id(), TRACE)).isEqualTo(GenerationAttempt.RETRY);
        assertThat(renderer.rendered).isNull();
        assertThat(statusStore.last().errorMessage()).contains("summary does not match the document");
    }

    @Test
    void anAttemptOverThirtySecondsIsAPdfTimeoutAndStoresNothing() {
        ConsultationDocument document = pending();
        reader.summary = summary(PATIENT);
        reader.onRead = () -> clock.advance(Duration.ofSeconds(31));

        assertThat(service.generate(document.id(), TRACE)).isEqualTo(GenerationAttempt.RETRY);
        assertThat(statusStore.last().errorMessage()).contains("PDF timeout (30s)");
        assertThat(renderer.rendered).isNull();
        assertThat(storage.stored).isEmpty();
    }

    @Test
    void theStoredErrorNeverCarriesTheCauseMessage() {
        ConsultationDocument document = pending();
        reader.failure = new SummaryUnavailableException("diagnosis: Acute pharyngitis", null);

        service.generate(document.id(), TRACE);

        assertThat(statusStore.last().errorMessage()).isPresent().get().asString()
                .doesNotContain("pharyngitis");
    }

    @Test
    void finishedUnknownOrExhaustedDocumentsAreSkipped() {
        ConsultationDocument exhausted = failed(3);
        ConsultationDocument available = pending().startGeneration().markAvailable("k.pdf", T0);
        repository.save(available);

        assertThat(service.generate(UUID.randomUUID(), TRACE)).isEqualTo(GenerationAttempt.SKIPPED);
        assertThat(service.generate(exhausted.id(), TRACE)).isEqualTo(GenerationAttempt.SKIPPED);
        assertThat(service.generate(available.id(), TRACE)).isEqualTo(GenerationAttempt.SKIPPED);
        assertThat(statusStore.transitions).isEmpty();
        assertThat(reader.reads).isZero();
    }

    @Test
    void aDocumentTakenByAnotherAttemptIsSkipped() {
        ConsultationDocument document = pending();
        statusStore.accept = false;

        assertThat(service.generate(document.id(), TRACE)).isEqualTo(GenerationAttempt.SKIPPED);
        assertThat(reader.reads).isZero();
    }

    @Test
    void resumableDocumentsComeFromTheStoreWithTheGivenLimit() {
        UUID id = UUID.randomUUID();
        statusStore.resumable = List.of(id);

        assertThat(service.resumable(25)).containsExactly(id);
        assertThat(statusStore.resumableLimit).isEqualTo(25);
    }

    /** One recorded status change: the row must still be {@code from} for it to apply. */
    record Transition(ConsultationDocument from, ConsultationDocument to) {
    }

    private final class RecordingStatusStore implements DocumentStatusStore {

        private final List<Transition> transitions = new ArrayList<>();
        private PdfGeneratedEvent event;
        private boolean accept = true;
        private List<UUID> resumable = List.of();
        private int resumableLimit;

        @Override
        public boolean transition(ConsultationDocument from, ConsultationDocument to) {
            if (!accept) {
                return false;
            }
            transitions.add(new Transition(from, to));
            repository.save(to);
            return true;
        }

        @Override
        public boolean complete(ConsultationDocument from, ConsultationDocument available, PdfGeneratedEvent event) {
            this.event = event;
            return transition(from, available);
        }

        @Override
        public List<UUID> findResumable(int limit) {
            resumableLimit = limit;
            return resumable;
        }

        List<DocumentStatus> statuses() {
            return transitions.stream().map(transition -> transition.to().status()).toList();
        }

        ConsultationDocument last() {
            return transitions.get(transitions.size() - 1).to();
        }
    }

    private static final class StubReader implements ConsultationSummaryReader {

        private ConsultationSummary summary;
        private RuntimeException failure;
        private Runnable onRead = () -> { };
        private int reads;

        @Override
        public ConsultationSummary read(UUID summaryId) {
            reads++;
            onRead.run();
            if (failure != null) {
                throw failure;
            }
            return summary;
        }
    }

    private static final class StubRenderer implements PdfRenderer {

        private ConsultationSummary rendered;
        private RuntimeException failure;

        @Override
        public byte[] render(ConsultationDocument document, ConsultationSummary summary, Instant issuedAt) {
            if (failure != null) {
                throw failure;
            }
            rendered = summary;
            return PDF;
        }
    }

    private static final class RecordingStorage implements DocumentStorage {

        private final Map<String, byte[]> stored = new LinkedHashMap<>();
        private RuntimeException failure;

        @Override
        public void store(String objectKey, byte[] pdf) {
            if (failure != null) {
                throw failure;
            }
            stored.put(objectKey, pdf);
        }

        @Override
        public DownloadLink presignDownload(String objectKey) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
