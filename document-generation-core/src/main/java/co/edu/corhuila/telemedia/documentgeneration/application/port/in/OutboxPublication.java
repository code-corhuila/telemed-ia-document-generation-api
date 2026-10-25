package co.edu.corhuila.telemedia.documentgeneration.application.port.in;

/** Publishes the events this service has committed and not published yet (DEC-008). */
@FunctionalInterface
public interface OutboxPublication {

    /** One round over the oldest pending events; returns how many were published. */
    int publishPending();
}
