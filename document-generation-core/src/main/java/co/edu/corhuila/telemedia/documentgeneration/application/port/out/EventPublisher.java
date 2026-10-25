package co.edu.corhuila.telemedia.documentgeneration.application.port.out;

/** The message broker (ADR-011). */
@FunctionalInterface
public interface EventPublisher {

    /**
     * Returns once the broker has taken the event.
     *
     * @throws EventPublicationException when it was not taken; it may be published again later
     */
    void publish(PendingEvent event);
}
