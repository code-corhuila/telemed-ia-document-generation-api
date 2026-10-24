package co.edu.corhuila.telemedia.documentgeneration.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import co.edu.corhuila.telemedia.documentgeneration.application.port.in.Caller;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DocumentAccessDeniedException;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.GenerateDocumentCommand;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.GenerationResult;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.IdempotencyKeyReusedException;
import co.edu.corhuila.telemedia.documentgeneration.domain.model.BusinessRuleViolationException;
import co.edu.corhuila.telemedia.documentgeneration.domain.model.DocumentStatus;

/** POST /generate rules (document-service.yaml, numeral 5.3.8), without server or database. */
class DocumentGenerationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-21T20:00:00Z");
    private static final Caller ADMIN = new Caller("admin-1", Set.of(Caller.ADMIN));
    private static final UUID SUMMARY = UUID.fromString("b1b2c3d4-2222-4222-8222-222222222222");
    private static final UUID PATIENT = UUID.fromString("c1b2c3d4-3333-4333-8333-333333333333");

    private final InMemoryIdempotentDocumentStore store = new InMemoryIdempotentDocumentStore();
    private final DocumentGenerationService service = new DocumentGenerationService(
            store, UUID::randomUUID, Clock.fixed(NOW, ZoneOffset.UTC));

    private static GenerateDocumentCommand command(String key, UUID summary, UUID patient) {
        return new GenerateDocumentCommand(key, summary, patient);
    }

    @Test
    void anAllowedServiceRequestsTheGenerationWithTheEventIdAsKey() {
        DocumentGenerationService withWorker = new DocumentGenerationService(
                store, UUID::randomUUID, Clock.fixed(NOW, ZoneOffset.UTC), Set.of("worker"));
        Caller worker = new Caller("worker", Set.of(Caller.SERVICE));

        GenerationResult first = withWorker.request(worker, command("event-0000001", SUMMARY, PATIENT));
        GenerationResult redelivered = withWorker.request(worker, command("event-0000001", SUMMARY, PATIENT));

        assertThat(first.created()).isTrue();
        assertThat(redelivered.created()).isFalse();
        assertThat(redelivered.document().id()).isEqualTo(first.document().id());
    }

    @Test
    void aServiceOutsideTheAllowedClientsIsDenied() {
        DocumentGenerationService withWorker = new DocumentGenerationService(
                store, UUID::randomUUID, Clock.fixed(NOW, ZoneOffset.UTC), Set.of("worker"));

        assertThatThrownBy(() -> withWorker.request(new Caller("notifications", Set.of(Caller.SERVICE)),
                command("key-00000001", SUMMARY, PATIENT))).isInstanceOf(DocumentAccessDeniedException.class);
        assertThatThrownBy(() -> service.request(new Caller("worker", Set.of(Caller.SERVICE)),
                command("key-00000001", SUMMARY, PATIENT))).isInstanceOf(DocumentAccessDeniedException.class);
        assertThat(store.documentCount()).isZero();
    }

    @Test
    void anAdministratorStillRequestsTheGenerationWhenServicesAreAllowed() {
        DocumentGenerationService withWorker = new DocumentGenerationService(
                store, UUID::randomUUID, Clock.fixed(NOW, ZoneOffset.UTC), Set.of("worker"));

        assertThat(withWorker.request(ADMIN, command("key-00000001", SUMMARY, PATIENT)).created()).isTrue();
    }

    @Test
    void aNewKeyCreatesThePendingDocument() {
        GenerationResult result = service.request(ADMIN, command("key-00000001", SUMMARY, PATIENT));

        assertThat(result.created()).isTrue();
        assertThat(result.document().status()).isEqualTo(DocumentStatus.PENDING);
        assertThat(result.document().summaryId()).isEqualTo(SUMMARY);
        assertThat(result.document().patientId()).isEqualTo(PATIENT);
        assertThat(result.document().createdAt()).isEqualTo(NOW);
        assertThat(store.documentCount()).isEqualTo(1);
    }

    @Test
    void theSameKeyAndBodyReturnTheSameDocumentWithoutCreatingAnother() {
        GenerationResult first = service.request(ADMIN, command("key-00000001", SUMMARY, PATIENT));

        GenerationResult retry = service.request(ADMIN, command("key-00000001", SUMMARY, PATIENT));

        assertThat(retry.created()).isFalse();
        assertThat(retry.document().id()).isEqualTo(first.document().id());
        assertThat(store.documentCount()).isEqualTo(1);
    }

    @Test
    void theSameKeyWithAnotherBodyIsABusinessRuleViolation() {
        service.request(ADMIN, command("key-00000001", SUMMARY, PATIENT));

        assertThatThrownBy(() -> service.request(ADMIN, command("key-00000001", UUID.randomUUID(), PATIENT)))
                .isInstanceOf(IdempotencyKeyReusedException.class)
                .isInstanceOf(BusinessRuleViolationException.class);
        assertThat(store.documentCount()).isEqualTo(1);
    }

    @Test
    void aSummaryThatAlreadyHasADocumentReturnsItWhateverTheKey() {
        GenerationResult first = service.request(ADMIN, command("key-00000001", SUMMARY, PATIENT));

        GenerationResult other = service.request(ADMIN, command("key-00000002", SUMMARY, PATIENT));

        assertThat(other.created()).isFalse();
        assertThat(other.document().id()).isEqualTo(first.document().id());
        assertThat(store.documentCount()).isEqualTo(1);
        assertThat(store.hasKey("key-00000002")).isFalse();
    }

    @Test
    void onlyAnAdministratorCanRequestGeneration() {
        Caller patient = new Caller(PATIENT.toString(), Set.of(Caller.PATIENT));

        assertThatThrownBy(() -> service.request(patient, command("key-00000001", SUMMARY, PATIENT)))
                .isInstanceOf(DocumentAccessDeniedException.class);
        assertThat(store.documentCount()).isZero();
    }

    @Test
    void theRequestHashIsStableAndDependsOnTheBody() {
        String hash = DocumentGenerationService.requestHash(command("key-00000001", SUMMARY, PATIENT));

        assertThat(hash).hasSize(64).matches("[0-9a-f]+");
        assertThat(DocumentGenerationService.requestHash(command("another-key-1", SUMMARY, PATIENT))).isEqualTo(hash);
        assertThat(DocumentGenerationService.requestHash(command("key-00000001", SUMMARY, UUID.randomUUID())))
                .isNotEqualTo(hash);
    }

    @Test
    void theKeyMustHave8To128Characters() {
        assertThatThrownBy(() -> command("short", SUMMARY, PATIENT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> command("k".repeat(129), SUMMARY, PATIENT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(command("k".repeat(128), SUMMARY, PATIENT).idempotencyKey()).hasSize(128);
        assertThat(command("k".repeat(8), SUMMARY, PATIENT).idempotencyKey()).hasSize(8);
    }
}
