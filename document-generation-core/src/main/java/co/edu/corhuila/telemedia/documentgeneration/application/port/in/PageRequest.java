package co.edu.corhuila.telemedia.documentgeneration.application.port.in;

/**
 * A bounded page (Anexo C, D-C5): {@code page} from 1, {@code limit} from 1 to 100.
 * The HTTP adapter rejects other values with 400 before they reach the core.
 */
public record PageRequest(int page, int limit) {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    public PageRequest {
        if (page < 1) {
            throw new IllegalArgumentException("page must be 1 or greater");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
    }

    public long offset() {
        return (long) (page - 1) * limit;
    }
}
