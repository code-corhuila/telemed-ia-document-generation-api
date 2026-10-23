package co.edu.corhuila.telemedia.documentgeneration.adapter.in.background;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import co.edu.corhuila.telemedia.documentgeneration.application.port.in.Caller;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DocumentAccessDeniedException;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DocumentGeneration;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.GenerateDocumentCommand;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.GenerationResult;
import co.edu.corhuila.telemedia.documentgeneration.domain.model.ConsultationDocument;

class SchedulingDocumentGenerationTest {

    private static final Instant T0 = Instant.parse("2026-10-23T14:00:00Z");
    private static final Caller ADMIN = new Caller("a0000000-0000-0000-0000-00000000000a", Set.of(Caller.ADMIN));
    private static final GenerateDocumentCommand COMMAND = new GenerateDocumentCommand("key-00001",
            UUID.randomUUID(), UUID.randomUUID());

    private final List<String> submitted = new ArrayList<>();
    private GenerationResult result;
    private RuntimeException failure;

    private final DocumentGeneration delegate = (caller, command) -> {
        if (failure != null) {
            throw failure;
        }
        return result;
    };
    private final SchedulingDocumentGeneration generation = new SchedulingDocumentGeneration(delegate,
            (documentId, correlationId) -> submitted.add(documentId + " " + correlationId), () -> "trace-1");

    private static ConsultationDocument pending() {
        return ConsultationDocument.request(UUID.randomUUID(), COMMAND.summaryId(), COMMAND.patientId(), T0);
    }

    @Test
    void aCreatedDocumentIsQueuedWithTheRequestCorrelationId() {
        result = new GenerationResult(pending(), true);

        assertThat(generation.request(ADMIN, COMMAND)).isSameAs(result);

        assertThat(submitted).containsExactly(result.document().id() + " trace-1");
    }

    @Test
    void anExistingDocumentStillPendingIsQueuedAgain() {
        result = new GenerationResult(pending(), false);

        generation.request(ADMIN, COMMAND);

        assertThat(submitted).containsExactly(result.document().id() + " trace-1");
    }

    @Test
    void anExistingDocumentAlreadyStartedIsNotQueued() {
        result = new GenerationResult(pending().startGeneration(), false);

        generation.request(ADMIN, COMMAND);

        assertThat(submitted).isEmpty();
    }

    @Test
    void aRejectedRequestQueuesNothing() {
        failure = new DocumentAccessDeniedException();

        assertThatThrownBy(() -> generation.request(ADMIN, COMMAND)).isSameAs(failure);

        assertThat(submitted).isEmpty();
    }
}
