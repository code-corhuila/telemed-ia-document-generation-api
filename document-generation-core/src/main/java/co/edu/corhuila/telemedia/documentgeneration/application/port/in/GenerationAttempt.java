package co.edu.corhuila.telemedia.documentgeneration.application.port.in;

/** Outcome of one PDF generation attempt (DEC-005, DEC-006). */
public enum GenerationAttempt {
    /** The PDF is stored and the document is AVAILABLE. */
    AVAILABLE,
    /** The attempt failed; the document is in ERROR and can be retried. */
    RETRY,
    /** The attempt failed and no retry is left: ERROR is final. */
    FAILED,
    /** Nothing to do: unknown, finished, exhausted or taken by another attempt. */
    SKIPPED
}
