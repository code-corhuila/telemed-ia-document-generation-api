package co.edu.corhuila.telemedia.documentgeneration.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ConsultationDocumentTest {

    private static final UUID DOCUMENT_ID = UUID.fromString("6f178b1a-9c0d-4c23-96ad-1e8da3b2ef6d");
    private static final UUID SUMMARY_ID = UUID.fromString("b1b2c3d4-2222-4222-8222-222222222222");
    private static final UUID PATIENT_ID = UUID.fromString("c1b2c3d4-3333-4333-8333-333333333333");
    private static final Instant CREATED_AT = Instant.parse("2026-10-21T15:00:00Z");
    private static final Instant GENERATED_AT = Instant.parse("2026-10-21T15:01:00Z");
    private static final String STORAGE_REFERENCE =
            "patients/c1b2c3d4-3333-4333-8333-333333333333/consultations/b1b2c3d4-2222-4222-8222-222222222222/6f178b1a-9c0d-4c23-96ad-1e8da3b2ef6d.pdf";

    private static ConsultationDocument pending() {
        return ConsultationDocument.request(DOCUMENT_ID, SUMMARY_ID, PATIENT_ID, CREATED_AT);
    }

    private static ConsultationDocument generating() {
        return pending().startGeneration();
    }

    private static ConsultationDocument failed() {
        return generating().markFailed("PDF timeout (30s)");
    }

    @Nested
    class Request {

        @Test
        void aNewDocumentIsPendingWithNoAttemptsAndNoResult() {
            ConsultationDocument document = pending();

            assertThat(document.id()).isEqualTo(DOCUMENT_ID);
            assertThat(document.summaryId()).isEqualTo(SUMMARY_ID);
            assertThat(document.patientId()).isEqualTo(PATIENT_ID);
            assertThat(document.format()).isEqualTo(DocumentFormat.PDF);
            assertThat(document.status()).isEqualTo(DocumentStatus.PENDING);
            assertThat(document.retryCount()).isZero();
            assertThat(document.createdAt()).isEqualTo(CREATED_AT);
            assertThat(document.storageReference()).isEmpty();
            assertThat(document.errorMessage()).isEmpty();
            assertThat(document.generatedAt()).isEmpty();
        }

        @Test
        void identifiersAndCreationTimeAreRequired() {
            assertThatThrownBy(() -> ConsultationDocument.request(null, SUMMARY_ID, PATIENT_ID, CREATED_AT))
                    .isInstanceOf(BusinessRuleViolationException.class);
            assertThatThrownBy(() -> ConsultationDocument.request(DOCUMENT_ID, null, PATIENT_ID, CREATED_AT))
                    .isInstanceOf(BusinessRuleViolationException.class);
            assertThatThrownBy(() -> ConsultationDocument.request(DOCUMENT_ID, SUMMARY_ID, null, CREATED_AT))
                    .isInstanceOf(BusinessRuleViolationException.class);
            assertThatThrownBy(() -> ConsultationDocument.request(DOCUMENT_ID, SUMMARY_ID, PATIENT_ID, null))
                    .isInstanceOf(BusinessRuleViolationException.class);
        }
    }

    @Nested
    class Generation {

        @Test
        void pendingStartsGeneratingWithoutCountingARetry() {
            ConsultationDocument document = generating();

            assertThat(document.status()).isEqualTo(DocumentStatus.GENERATING);
            assertThat(document.retryCount()).isZero();
        }

        @Test
        void generatingBecomesAvailableWithItsStorageReferenceAndTime() {
            ConsultationDocument document = generating().markAvailable(STORAGE_REFERENCE, GENERATED_AT);

            assertThat(document.status()).isEqualTo(DocumentStatus.AVAILABLE);
            assertThat(document.storageReference()).contains(STORAGE_REFERENCE);
            assertThat(document.generatedAt()).contains(GENERATED_AT);
            assertThat(document.errorMessage()).isEmpty();
        }

        @Test
        void storageReferenceMustHave1To512Characters() {
            assertThatThrownBy(() -> generating().markAvailable(" ", GENERATED_AT))
                    .isInstanceOf(BusinessRuleViolationException.class);
            assertThatThrownBy(() -> generating().markAvailable(null, GENERATED_AT))
                    .isInstanceOf(BusinessRuleViolationException.class);
            assertThatThrownBy(() -> generating().markAvailable("x".repeat(513), GENERATED_AT))
                    .isInstanceOf(BusinessRuleViolationException.class);
            assertThat(generating().markAvailable("x".repeat(512), GENERATED_AT).storageReference())
                    .contains("x".repeat(512));
        }

        @Test
        void generatedAtIsRequiredToBeAvailable() {
            assertThatThrownBy(() -> generating().markAvailable(STORAGE_REFERENCE, null))
                    .isInstanceOf(BusinessRuleViolationException.class);
        }

        @Test
        void onlyAGeneratingDocumentCanBecomeAvailable() {
            assertThatThrownBy(() -> pending().markAvailable(STORAGE_REFERENCE, GENERATED_AT))
                    .isInstanceOf(InvalidStatusTransitionException.class);
            assertThatThrownBy(() -> failed().markAvailable(STORAGE_REFERENCE, GENERATED_AT))
                    .isInstanceOf(InvalidStatusTransitionException.class);
        }

        @Test
        void anAvailableDocumentIsTerminal() {
            ConsultationDocument available = generating().markAvailable(STORAGE_REFERENCE, GENERATED_AT);

            assertThatThrownBy(available::startGeneration).isInstanceOf(InvalidStatusTransitionException.class);
            assertThatThrownBy(() -> available.markFailed("late failure"))
                    .isInstanceOf(InvalidStatusTransitionException.class);
        }

        @Test
        void aGeneratingDocumentCannotStartAgain() {
            assertThatThrownBy(() -> generating().startGeneration())
                    .isInstanceOf(InvalidStatusTransitionException.class);
        }

        @Test
        void transitionsReturnANewInstanceAndLeaveTheOriginalUntouched() {
            ConsultationDocument original = pending();

            ConsultationDocument next = original.startGeneration();

            assertThat(next).isNotSameAs(original);
            assertThat(original.status()).isEqualTo(DocumentStatus.PENDING);
        }
    }

    @Nested
    class Failure {

        @Test
        void generatingCanFailWithAMessage() {
            ConsultationDocument document = failed();

            assertThat(document.status()).isEqualTo(DocumentStatus.ERROR);
            assertThat(document.errorMessage()).contains("PDF timeout (30s)");
            assertThat(document.storageReference()).isEmpty();
            assertThat(document.generatedAt()).isEmpty();
        }

        @Test
        void pendingCanFailDirectlyWhenTheSummaryIsInvalid() {
            ConsultationDocument document = pending().markFailed("invalid post-consultation summary");

            assertThat(document.status()).isEqualTo(DocumentStatus.ERROR);
            assertThat(document.retryCount()).isZero();
        }

        @Test
        void errorMessageMustHave1To2000Characters() {
            assertThatThrownBy(() -> generating().markFailed(""))
                    .isInstanceOf(BusinessRuleViolationException.class);
            assertThatThrownBy(() -> generating().markFailed(null))
                    .isInstanceOf(BusinessRuleViolationException.class);
            assertThatThrownBy(() -> generating().markFailed("e".repeat(2001)))
                    .isInstanceOf(BusinessRuleViolationException.class);
            assertThat(generating().markFailed("e".repeat(2000)).errorMessage()).contains("e".repeat(2000));
        }

        @Test
        void aFailedDocumentCannotFailAgain() {
            assertThatThrownBy(() -> failed().markFailed("again"))
                    .isInstanceOf(InvalidStatusTransitionException.class);
        }
    }

    @Nested
    class Retry {

        @Test
        void aFailedDocumentRetriesCountingTheAttemptAndClearingTheError() {
            ConsultationDocument retried = failed().startGeneration();

            assertThat(retried.status()).isEqualTo(DocumentStatus.GENERATING);
            assertThat(retried.retryCount()).isEqualTo(1);
            assertThat(retried.errorMessage()).isEmpty();
        }

        @Test
        void retriesAreBoundedToThree() {
            ConsultationDocument document = failed();
            for (int attempt = 1; attempt <= ConsultationDocument.MAX_RETRIES; attempt++) {
                assertThat(document.canRetry()).isTrue();
                document = document.startGeneration().markFailed("attempt " + attempt + " failed");
                assertThat(document.retryCount()).isEqualTo(attempt);
            }

            assertThat(document.canRetry()).isFalse();
            assertThatThrownBy(document::startGeneration).isInstanceOf(RetryLimitExceededException.class);
        }

        @Test
        void onlyAFailedDocumentCanRetry() {
            assertThat(pending().canRetry()).isFalse();
            assertThat(generating().canRetry()).isFalse();
            assertThat(generating().markAvailable(STORAGE_REFERENCE, GENERATED_AT).canRetry()).isFalse();
        }

        @Test
        void theRetryLimitIsAlsoABusinessRuleViolation() {
            assertThat(new RetryLimitExceededException(DOCUMENT_ID)).isInstanceOf(BusinessRuleViolationException.class);
        }
    }

    @Nested
    class Ownership {

        @Test
        void aDocumentBelongsOnlyToItsPatient() {
            assertThat(pending().isOwnedBy(PATIENT_ID)).isTrue();
            assertThat(pending().isOwnedBy(UUID.randomUUID())).isFalse();
            assertThat(pending().isOwnedBy(null)).isFalse();
        }
    }

    @Nested
    class Rehydration {

        @Test
        void aConsistentStoredDocumentIsRestored() {
            ConsultationDocument document = ConsultationDocument.rehydrate(
                    DOCUMENT_ID, SUMMARY_ID, PATIENT_ID, DocumentStatus.AVAILABLE, 2,
                    STORAGE_REFERENCE, null, CREATED_AT, GENERATED_AT);

            assertThat(document.status()).isEqualTo(DocumentStatus.AVAILABLE);
            assertThat(document.retryCount()).isEqualTo(2);
            assertThat(document.storageReference()).contains(STORAGE_REFERENCE);
        }

        @Test
        void availableRequiresStorageReferenceAndGeneratedAt() {
            assertThatThrownBy(() -> ConsultationDocument.rehydrate(
                    DOCUMENT_ID, SUMMARY_ID, PATIENT_ID, DocumentStatus.AVAILABLE, 0,
                    null, null, CREATED_AT, GENERATED_AT))
                    .isInstanceOf(BusinessRuleViolationException.class);
            assertThatThrownBy(() -> ConsultationDocument.rehydrate(
                    DOCUMENT_ID, SUMMARY_ID, PATIENT_ID, DocumentStatus.AVAILABLE, 0,
                    STORAGE_REFERENCE, null, CREATED_AT, null))
                    .isInstanceOf(BusinessRuleViolationException.class);
        }

        @Test
        void onlyAvailableCarriesStorageReferenceOrGeneratedAt() {
            assertThatThrownBy(() -> ConsultationDocument.rehydrate(
                    DOCUMENT_ID, SUMMARY_ID, PATIENT_ID, DocumentStatus.PENDING, 0,
                    STORAGE_REFERENCE, null, CREATED_AT, null))
                    .isInstanceOf(BusinessRuleViolationException.class);
            assertThatThrownBy(() -> ConsultationDocument.rehydrate(
                    DOCUMENT_ID, SUMMARY_ID, PATIENT_ID, DocumentStatus.GENERATING, 0,
                    null, null, CREATED_AT, GENERATED_AT))
                    .isInstanceOf(BusinessRuleViolationException.class);
        }

        @Test
        void errorRequiresAMessageAndOnlyErrorCarriesOne() {
            assertThatThrownBy(() -> ConsultationDocument.rehydrate(
                    DOCUMENT_ID, SUMMARY_ID, PATIENT_ID, DocumentStatus.ERROR, 0,
                    null, null, CREATED_AT, null))
                    .isInstanceOf(BusinessRuleViolationException.class);
            assertThatThrownBy(() -> ConsultationDocument.rehydrate(
                    DOCUMENT_ID, SUMMARY_ID, PATIENT_ID, DocumentStatus.PENDING, 0,
                    null, "stale error", CREATED_AT, null))
                    .isInstanceOf(BusinessRuleViolationException.class);
        }

        @Test
        void retryCountMustBeBetweenZeroAndTheLimit() {
            assertThatThrownBy(() -> ConsultationDocument.rehydrate(
                    DOCUMENT_ID, SUMMARY_ID, PATIENT_ID, DocumentStatus.PENDING, -1,
                    null, null, CREATED_AT, null))
                    .isInstanceOf(BusinessRuleViolationException.class);
            assertThatThrownBy(() -> ConsultationDocument.rehydrate(
                    DOCUMENT_ID, SUMMARY_ID, PATIENT_ID, DocumentStatus.ERROR, ConsultationDocument.MAX_RETRIES + 1,
                    null, "failed", CREATED_AT, null))
                    .isInstanceOf(BusinessRuleViolationException.class);
        }

        @Test
        void statusIsRequired() {
            assertThatThrownBy(() -> ConsultationDocument.rehydrate(
                    DOCUMENT_ID, SUMMARY_ID, PATIENT_ID, null, 0,
                    null, null, CREATED_AT, null))
                    .isInstanceOf(BusinessRuleViolationException.class);
        }
    }
}
