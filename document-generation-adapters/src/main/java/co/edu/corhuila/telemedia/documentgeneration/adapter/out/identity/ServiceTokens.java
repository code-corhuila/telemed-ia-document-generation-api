package co.edu.corhuila.telemedia.documentgeneration.adapter.out.identity;

/** The token this service presents to other services (norma 5.7). */
public interface ServiceTokens {

    /** No credentials configured: calls go without {@code Authorization}. */
    ServiceTokens NONE = new ServiceTokens() {

        @Override
        public String token() {
            return "";
        }

        @Override
        public void invalidate(String rejected) {
            // Nothing to renew.
        }
    };

    /**
     * @return a valid token, or an empty string when no credentials are configured
     * @throws ServiceTokenException if a token cannot be obtained
     */
    String token();

    /** Another service rejected {@code rejected}: the next {@link #token()} gets a new one. */
    void invalidate(String rejected);
}
