package co.edu.corhuila.telemedia.documentgeneration.adapter.out.persistence;

import java.util.UUID;

import co.edu.corhuila.telemedia.documentgeneration.application.port.out.IdGenerator;

/** Random (version 4) UUIDs for new resources (Anexo C, UUID identifiers). */
public final class UuidGenerator implements IdGenerator {

    @Override
    public UUID newId() {
        return UUID.randomUUID();
    }
}
