package co.edu.corhuila.telemedia.documentgeneration.application.usecase;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DocumentPdfGeneration;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.GenerationAttempt;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.ConsultationDocumentRepository;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.ConsultationSummary;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.ConsultationSummaryReader;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.DocumentStatusStore;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.DocumentStorage;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.GenerationMetrics;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.IdGenerator;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.PdfGeneratedEvent;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.PdfRenderer;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.StorageUnavailableException;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.SummaryUnavailableException;
import co.edu.corhuila.telemedia.documentgeneration.domain.model.ConsultationDocument;
import co.edu.corhuila.telemedia.documentgeneration.domain.model.DocumentStatus;

/**
 * One PDF generation attempt (DEC-005, DEC-006):
 * <ol>
 *   <li>{@code PENDING|ERROR -> GENERATING} (a retry counts); a document left GENERATING by a
 *       restart continues without a new transition;</li>
 *   <li>read the summary from Medical Consultation, render the PDF, store it under the
 *       ADR-015 key;</li>
 *   <li>{@code GENERATING -> AVAILABLE} together with the {@code ConsultationPdfGenerated}
 *       outbox row.</li>
 * </ol>
 * Any failure, or an attempt longer than its time limit, leaves ERROR with a neutral
 * message: causes may carry clinical data, so they are never stored on the document.
 */
public final class PdfGenerationService implements DocumentPdfGeneration {

    static final String SUMMARY_UNAVAILABLE = "summary unavailable";
    static final String SUMMARY_MISMATCH = "summary does not match the document";
    static final String PDF_ERROR = "PDF error";
    static final String STORAGE_UNAVAILABLE = "storage unavailable";

    private final ConsultationDocumentRepository repository;
    private final DocumentStatusStore statusStore;
    private final ConsultationSummaryReader summaries;
    private final PdfRenderer renderer;
    private final DocumentStorage storage;
    private final IdGenerator ids;
    private final GenerationMetrics metrics;
    private final Clock clock;
    private final Duration attemptTimeout;

    public PdfGenerationService(ConsultationDocumentRepository repository, DocumentStatusStore statusStore,
            ConsultationSummaryReader summaries, PdfRenderer renderer, DocumentStorage storage, IdGenerator ids,
            GenerationMetrics metrics, Clock clock, Duration attemptTimeout) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.statusStore = Objects.requireNonNull(statusStore, "statusStore");
        this.summaries = Objects.requireNonNull(summaries, "summaries");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.attemptTimeout = Objects.requireNonNull(attemptTimeout, "attemptTimeout");
    }

    @Override
    public GenerationAttempt generate(UUID documentId, String correlationId) {
        Instant started = clock.instant();
        GenerationAttempt outcome = attempt(documentId, correlationId);
        metrics.attemptFinished(outcome, Duration.between(started, clock.instant()));
        return outcome;
    }

    private GenerationAttempt attempt(UUID documentId, String correlationId) {
        Objects.requireNonNull(correlationId, "correlationId");
        Optional<ConsultationDocument> found = repository.findById(Objects.requireNonNull(documentId, "documentId"));
        if (found.isEmpty()) {
            return GenerationAttempt.SKIPPED;
        }
        ConsultationDocument generating = start(found.get());
        if (generating == null) {
            return GenerationAttempt.SKIPPED;
        }
        Instant deadline = clock.instant().plus(attemptTimeout);
        try {
            ConsultationSummary summary = summaries.read(generating.summaryId(), correlationId);
            checkDeadline(deadline);
            if (!summary.patientId().equals(generating.patientId()) || !summary.summaryId().equals(generating.summaryId())) {
                throw new AttemptFailed(SUMMARY_MISMATCH);
            }
            byte[] pdf = renderer.render(generating, summary, clock.instant());
            checkDeadline(deadline);
            String key = objectKey(generating);
            storage.store(key, pdf);
            checkDeadline(deadline);
            Instant generatedAt = clock.instant();
            ConsultationDocument available = generating.markAvailable(key, generatedAt);
            PdfGeneratedEvent event = new PdfGeneratedEvent(ids.newId(), generating.id(), generating.summaryId(),
                    summary.appointmentId(), generating.patientId(), generatedAt, correlationId);
            if (!statusStore.complete(generating, available, event)) {
                return GenerationAttempt.SKIPPED;
            }
            metrics.documentAvailable(Duration.between(generating.createdAt(), generatedAt));
            return GenerationAttempt.AVAILABLE;
        } catch (AttemptFailed e) {
            return fail(generating, e.getMessage());
        } catch (SummaryUnavailableException e) {
            return fail(generating, SUMMARY_UNAVAILABLE);
        } catch (StorageUnavailableException e) {
            return fail(generating, STORAGE_UNAVAILABLE);
        } catch (RuntimeException e) {
            // PdfRenderingException and any unexpected failure of the attempt (DEC-005: "PDF error").
            return fail(generating, PDF_ERROR);
        }
    }

    @Override
    public List<UUID> resumable(int limit) {
        return statusStore.findResumable(limit);
    }

    /** ADR-015: derived from the document, so a retry overwrites the same object. */
    static String objectKey(ConsultationDocument document) {
        return "patients/" + document.patientId() + "/consultations/" + document.summaryId() + "/"
                + document.id() + ".pdf";
    }

    /** @return the GENERATING document, or {@code null} when there is nothing to do */
    private ConsultationDocument start(ConsultationDocument document) {
        if (document.status() == DocumentStatus.GENERATING) {
            return document;
        }
        boolean startable = document.status() == DocumentStatus.PENDING || document.canRetry();
        if (!startable) {
            return null;
        }
        ConsultationDocument generating = document.startGeneration();
        return statusStore.transition(document, generating) ? generating : null;
    }

    private GenerationAttempt fail(ConsultationDocument generating, String message) {
        ConsultationDocument failed = generating.markFailed(message);
        if (!statusStore.transition(generating, failed)) {
            return GenerationAttempt.SKIPPED;
        }
        return failed.canRetry() ? GenerationAttempt.RETRY : GenerationAttempt.FAILED;
    }

    private void checkDeadline(Instant deadline) {
        if (clock.instant().isAfter(deadline)) {
            throw new AttemptFailed("PDF timeout (" + attemptTimeout.toSeconds() + "s)");
        }
    }

    /** A failure decided by this use case, with the neutral message to store. */
    private static final class AttemptFailed extends RuntimeException {

        private static final long serialVersionUID = 1L;

        AttemptFailed(String message) {
            super(message, null, false, false);
        }
    }
}
