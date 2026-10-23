package co.edu.corhuila.telemedia.documentgeneration.application.port.out;

/** The summary could not be read; the cause is logged, never stored on the document. */
public class SummaryUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SummaryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
