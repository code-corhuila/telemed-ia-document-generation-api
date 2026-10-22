package co.edu.corhuila.telemedia.documentgeneration.application.port.out;

import java.util.UUID;

/** Source of new resource identifiers (Anexo C, output port). */
public interface IdGenerator {

    UUID newId();
}
