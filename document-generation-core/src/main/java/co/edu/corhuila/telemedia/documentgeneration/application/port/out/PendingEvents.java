package co.edu.corhuila.telemedia.documentgeneration.application.port.out;

/** The outbox of this service, as the publication reads it (norma 5.3.11, DEC-008). */
public interface PendingEvents {

    /** What happened to one event of the batch. */
    record Outcome(Status status, String error) {

        public enum Status { PUBLISHED, FAILED, NOT_ATTEMPTED }

        public static Outcome published() {
            return new Outcome(Status.PUBLISHED, null);
        }

        public static Outcome failed(String error) {
            return new Outcome(Status.FAILED, error);
        }

        public static Outcome notAttempted() {
            return new Outcome(Status.NOT_ATTEMPTED, null);
        }
    }

    /** Publishes one event and says how it went. */
    @FunctionalInterface
    interface Publication {
        Outcome publish(PendingEvent event);
    }

    /**
     * Hands the oldest unpublished events, at most {@code limit}, to {@code publication} one
     * by one, and stores each outcome: a published event is never handed again, a failed one
     * keeps its attempt count and last error, a not attempted one is left as it was. Two
     * callers never get the same event at the same time.
     */
    void publishOldest(int limit, Publication publication);
}
