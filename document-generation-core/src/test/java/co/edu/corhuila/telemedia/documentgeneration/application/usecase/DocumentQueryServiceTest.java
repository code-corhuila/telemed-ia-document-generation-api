package co.edu.corhuila.telemedia.documentgeneration.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import co.edu.corhuila.telemedia.documentgeneration.application.port.in.Caller;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.DocumentAccessDeniedException;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.Page;
import co.edu.corhuila.telemedia.documentgeneration.application.port.in.PageRequest;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.DocumentNotFoundException;
import co.edu.corhuila.telemedia.documentgeneration.domain.model.ConsultationDocument;
import co.edu.corhuila.telemedia.documentgeneration.domain.model.DocumentStatus;

/** DEC-004 on the read use cases: patient role, own documents only, 403 before 404. */
class DocumentQueryServiceTest {

    private static final UUID ANA = UUID.fromString("c1b2c3d4-3333-4333-8333-333333333333");
    private static final UUID LUIS = UUID.fromString("d1b2c3d4-4444-4444-8444-444444444444");
    private static final Instant T0 = Instant.parse("2026-10-21T15:00:00Z");

    private final InMemoryConsultationDocumentRepository repository = new InMemoryConsultationDocumentRepository();
    private final DocumentQueryService service = new DocumentQueryService(repository);

    private ConsultationDocument anaOld;
    private ConsultationDocument anaNew;
    private ConsultationDocument anaFailed;
    private ConsultationDocument luisDoc;

    private static Caller patient(UUID id) {
        return new Caller(id.toString(), Set.of(Caller.PATIENT));
    }

    private static ConsultationDocument pending(UUID patient, Instant createdAt) {
        return ConsultationDocument.request(UUID.randomUUID(), UUID.randomUUID(), patient, createdAt);
    }

    @BeforeEach
    void seed() {
        anaOld = pending(ANA, T0);
        anaNew = pending(ANA, T0.plusSeconds(120));
        anaFailed = pending(ANA, T0.plusSeconds(60)).startGeneration().markFailed("PDF timeout (30s)");
        luisDoc = pending(LUIS, T0.plusSeconds(300));
        for (ConsultationDocument document : new ConsultationDocument[] {anaOld, anaNew, anaFailed, luisDoc}) {
            repository.save(document);
        }
    }

    @Nested
    class ListMine {

        @Test
        void aPatientSeesOnlyTheirDocumentsMostRecentFirst() {
            Page<ConsultationDocument> page = service.listMine(patient(ANA), Optional.empty(), new PageRequest(1, 20));

            assertThat(page.items()).containsExactly(anaNew, anaFailed, anaOld);
            assertThat(page.total()).isEqualTo(3);
            assertThat(page.totalPages()).isEqualTo(1);
        }

        @Test
        void theStatusFilterNarrowsTheResult() {
            Page<ConsultationDocument> page = service.listMine(patient(ANA), Optional.of(DocumentStatus.ERROR),
                    new PageRequest(1, 20));

            assertThat(page.items()).containsExactly(anaFailed);
            assertThat(page.total()).isEqualTo(1);
        }

        @Test
        void thePageIsBoundedByItsLimit() {
            Page<ConsultationDocument> second = service.listMine(patient(ANA), Optional.empty(), new PageRequest(2, 2));

            assertThat(second.items()).containsExactly(anaOld);
            assertThat(second.page()).isEqualTo(2);
            assertThat(second.limit()).isEqualTo(2);
            assertThat(second.total()).isEqualTo(3);
            assertThat(second.totalPages()).isEqualTo(2);
        }

        @Test
        void aPageBeyondTheResultsIsEmpty() {
            Page<ConsultationDocument> page = service.listMine(patient(ANA), Optional.empty(), new PageRequest(9, 20));

            assertThat(page.items()).isEmpty();
            assertThat(page.total()).isEqualTo(3);
        }

        @Test
        void aPatientWithoutDocumentsGetsAnEmptyPage() {
            Page<ConsultationDocument> page = service.listMine(patient(UUID.randomUUID()), Optional.empty(),
                    new PageRequest(1, 20));

            assertThat(page.items()).isEmpty();
            assertThat(page.total()).isZero();
            assertThat(page.totalPages()).isZero();
        }

        @Test
        void onlyPatientsCanList() {
            Caller admin = new Caller(ANA.toString(), Set.of(Caller.ADMIN));

            assertThatThrownBy(() -> service.listMine(admin, Optional.empty(), new PageRequest(1, 20)))
                    .isInstanceOf(DocumentAccessDeniedException.class);
        }
    }

    @Nested
    class GetById {

        @Test
        void theOwnerGetsTheDocument() {
            assertThat(service.getById(patient(ANA), anaFailed.id())).isEqualTo(anaFailed);
        }

        @Test
        void anotherPatientIsDenied() {
            assertThatThrownBy(() -> service.getById(patient(ANA), luisDoc.id()))
                    .isInstanceOf(DocumentAccessDeniedException.class);
        }

        @Test
        void anUnknownDocumentIsNotFound() {
            assertThatThrownBy(() -> service.getById(patient(ANA), UUID.randomUUID()))
                    .isInstanceOf(DocumentNotFoundException.class);
        }

        @Test
        void aNonPatientIsDeniedBeforeLookingTheDocumentUp() {
            Caller professional = new Caller(LUIS.toString(), Set.of("PROFESSIONAL"));

            assertThatThrownBy(() -> service.getById(professional, UUID.randomUUID()))
                    .isInstanceOf(DocumentAccessDeniedException.class);
        }

        @Test
        void aPatientTokenWhoseSubjectIsNotAUuidIsDenied() {
            Caller odd = new Caller("not-a-uuid", Set.of(Caller.PATIENT));

            assertThatThrownBy(() -> service.getById(odd, anaOld.id()))
                    .isInstanceOf(DocumentAccessDeniedException.class);
        }
    }
}
