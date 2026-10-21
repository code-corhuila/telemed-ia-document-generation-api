package co.edu.corhuila.telemedia.documentgeneration.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The allowed transitions must be exactly the ones enforced by the database
 * trigger {@code trg_consultation_document_status_transition}
 * (telemed-ia-document-generation-db, ddl-triggers-003).
 */
class DocumentStatusTest {

    private static final Set<String> ALLOWED = Set.of(
            "PENDING->GENERATING",
            "PENDING->ERROR",
            "GENERATING->AVAILABLE",
            "GENERATING->ERROR",
            "ERROR->GENERATING");

    @ParameterizedTest
    @EnumSource(DocumentStatus.class)
    void transitionsMatchTheDatabaseTrigger(DocumentStatus from) {
        for (DocumentStatus to : DocumentStatus.values()) {
            boolean expected = ALLOWED.contains(from + "->" + to);
            assertThat(from.canTransitionTo(to))
                    .as("%s -> %s", from, to)
                    .isEqualTo(expected);
        }
    }

    @ParameterizedTest
    @EnumSource(value = DocumentStatus.class, names = {"AVAILABLE"})
    void availableIsTerminal(DocumentStatus status) {
        for (DocumentStatus to : DocumentStatus.values()) {
            assertThat(status.canTransitionTo(to)).isFalse();
        }
    }
}
