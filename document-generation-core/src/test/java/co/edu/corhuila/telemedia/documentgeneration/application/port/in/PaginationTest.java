package co.edu.corhuila.telemedia.documentgeneration.application.port.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** D-C5: page from 1, limit 1 to 100, totalPages 0 when there are no results. */
class PaginationTest {

    @Test
    void pageAndLimitAreBounded() {
        assertThatThrownBy(() -> new PageRequest(0, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PageRequest(1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PageRequest(1, 101)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new PageRequest(1, PageRequest.MAX_LIMIT).limit()).isEqualTo(100);
        assertThat(PageRequest.DEFAULT_LIMIT).isEqualTo(20);
    }

    @Test
    void theOffsetStartsAtZero() {
        assertThat(new PageRequest(1, 20).offset()).isZero();
        assertThat(new PageRequest(3, 20).offset()).isEqualTo(40);
    }

    @Test
    void totalPagesRoundsUpAndIsZeroWithoutResults() {
        assertThat(Page.of(List.of(), new PageRequest(1, 20), 0).totalPages()).isZero();
        assertThat(Page.of(List.of(), new PageRequest(1, 20), 37).totalPages()).isEqualTo(2);
        assertThat(Page.of(List.of(), new PageRequest(1, 20), 40).totalPages()).isEqualTo(2);
    }

    @Test
    void aPageCannotHoldMoreThanItsLimit() {
        assertThatThrownBy(() -> Page.of(List.of("a", "b"), new PageRequest(1, 1), 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theCallerIsAPatientOnlyWithThePatientRoleAndAUuidSubject() {
        UUID id = UUID.randomUUID();

        assertThat(new Caller(id.toString(), Set.of(Caller.PATIENT)).patientId()).contains(id);
        assertThat(new Caller(id.toString(), Set.of(Caller.ADMIN)).patientId()).isEmpty();
        assertThat(new Caller("patient-1", Set.of(Caller.PATIENT)).patientId()).isEmpty();
        assertThat(new Caller("1-1-1-1-1", Set.of(Caller.PATIENT)).patientId()).isEmpty();
    }
}
