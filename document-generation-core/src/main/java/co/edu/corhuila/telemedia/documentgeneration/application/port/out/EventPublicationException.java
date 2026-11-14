package co.edu.corhuila.telemedia.documentgeneration.application.port.out;

/** The broker did not take the event; the reason is safe to store and to log. */
public class EventPublicationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public EventPublicationException(String reason) {
        super(reason);
    }
}
