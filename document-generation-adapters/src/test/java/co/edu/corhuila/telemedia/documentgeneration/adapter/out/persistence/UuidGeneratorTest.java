package co.edu.corhuila.telemedia.documentgeneration.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UuidGeneratorTest {

    @Test
    void identifiersAreRandomVersion4Uuids() {
        UuidGenerator ids = new UuidGenerator();

        assertThat(ids.newId()).isNotEqualTo(ids.newId());
        assertThat(ids.newId().version()).isEqualTo(4);
    }
}
