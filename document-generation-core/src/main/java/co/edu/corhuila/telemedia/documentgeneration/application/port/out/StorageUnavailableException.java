package co.edu.corhuila.telemedia.documentgeneration.application.port.out;

/** Object storage cannot be used right now (translated to {@code 503 SERVICE_UNAVAILABLE}). */
public class StorageUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public StorageUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
