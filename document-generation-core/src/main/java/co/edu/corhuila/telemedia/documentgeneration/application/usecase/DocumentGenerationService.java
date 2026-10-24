package co.edu.corhuila.telemedia.documentgeneration.application.usecase;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

import co.edu.corhuila.telemedia.documentgeneration.application.port.in.Caller;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DocumentAccessDeniedException;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DocumentGeneration;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.GenerateDocumentCommand;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.GenerationResult;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.IdempotencyKeyReusedException;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.CreationOutcome;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.IdGenerator;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.IdempotentDocumentStore;
import co.edu.corhuila.telemedia.documentgeneration.domain.model.ConsultationDocument;

/**
 * POST /generate rules of document-service.yaml:
 * <ul>
 *   <li>only {@code ADMIN}, or a {@code SERVICE} whose subject is an allowed client, may call it:
 *       the regular trigger is the consumer of the summary event (DEC-007);</li>
 *   <li>a new key creates the PENDING document ({@code 201});</li>
 *   <li>the same key with the same body returns the same document ({@code 200});</li>
 *   <li>the same key with another body is a business rule violation ({@code 422});</li>
 *   <li>a summary that already has a document returns it ({@code 200}), whatever the key.</li>
 * </ul>
 */
public final class DocumentGenerationService implements DocumentGeneration {

    private final IdempotentDocumentStore store;
    private final IdGenerator ids;
    private final Clock clock;
    private final Set<String> allowedServiceClients;

    /** Only administrators may request a generation. */
    public DocumentGenerationService(IdempotentDocumentStore store, IdGenerator ids, Clock clock) {
        this(store, ids, clock, Set.of());
    }

    /** Administrators, and the services whose client id is in {@code allowedServiceClients}. */
    public DocumentGenerationService(IdempotentDocumentStore store, IdGenerator ids, Clock clock,
            Set<String> allowedServiceClients) {
        this.store = Objects.requireNonNull(store, "store");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.allowedServiceClients = Set.copyOf(allowedServiceClients);
    }

    @Override
    public GenerationResult request(Caller caller, GenerateDocumentCommand command) {
        if (!mayRequest(Objects.requireNonNull(caller, "caller"))) {
            throw new DocumentAccessDeniedException();
        }
        String hash = requestHash(command);
        ConsultationDocument candidate = ConsultationDocument.request(
                ids.newId(), command.summaryId(), command.patientId(), clock.instant());
        CreationOutcome outcome = store.createOnce(command.idempotencyKey(), hash, candidate);
        return switch (outcome) {
            case CreationOutcome.Created created -> new GenerationResult(created.document(), true);
            case CreationOutcome.KeyAlreadyUsed used -> {
                if (!used.requestHash().equals(hash)) {
                    throw new IdempotencyKeyReusedException();
                }
                yield new GenerationResult(used.document(), false);
            }
            case CreationOutcome.SummaryAlreadyHasDocument existing -> new GenerationResult(existing.document(), false);
        };
    }

    private boolean mayRequest(Caller caller) {
        return caller.hasRole(Caller.ADMIN)
                || (caller.hasRole(Caller.SERVICE) && allowedServiceClients.contains(caller.subject()));
    }

    /** SHA-256 of the canonical request, stored with the key to recognise the same intent. */
    static String requestHash(GenerateDocumentCommand command) {
        String canonical = command.summaryId() + ":" + command.patientId();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
