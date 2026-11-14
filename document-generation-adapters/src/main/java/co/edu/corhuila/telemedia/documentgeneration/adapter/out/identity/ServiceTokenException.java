package co.edu.corhuila.telemedia.documentgeneration.adapter.out.identity;

/**
 * No service token could be obtained. The message names the status or the reason only,
 * never the secret or a token.
 */
public class ServiceTokenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final boolean retryable;

    public ServiceTokenException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    /** {@code true} for network failures, {@code 429} and {@code 5xx} (norma 5.7.2). */
    public boolean retryable() {
        return retryable;
    }
}
