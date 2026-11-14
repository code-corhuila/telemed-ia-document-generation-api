package co.edu.corhuila.telemedia.documentgeneration.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DomainExceptionsTest {

    @Test
    void invalidTransitionNamesBothStates() {
        assertThat(new InvalidStatusTransitionException(DocumentStatus.PENDING, DocumentStatus.AVAILABLE))
                .hasMessageContaining("PENDING")
                .hasMessageContaining("AVAILABLE");
    }

    @Test
    void allDomainErrorsShareTheDomainBaseType() {
        assertThat(new InvalidStatusTransitionException(DocumentStatus.PENDING, DocumentStatus.AVAILABLE))
                .isInstanceOf(DomainException.class);
        assertThat(new BusinessRuleViolationException("rule")).isInstanceOf(DomainException.class);
    }
}
