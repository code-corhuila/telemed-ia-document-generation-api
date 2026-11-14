package co.edu.corhuila.telemedia.documentgeneration.application.port.out;

import java.time.Duration;

import co.edu.corhuila.telemedia.documentgeneration.application.port.in.GenerationAttempt;

/**
 * What the PDF generation reports for its service level objectives (NFR-005): the outcome
 * and duration of each attempt, and how long a patient waited from the request until the
 * PDF was available (freshness of the asynchronous flow). Identifiers are never reported.
 */
public interface GenerationMetrics {

    /** Reports nothing. */
    GenerationMetrics NONE = new GenerationMetrics() {

        @Override
        public void attemptFinished(GenerationAttempt outcome, Duration duration) {
            // Nothing to report.
        }

        @Override
        public void documentAvailable(Duration sinceRequested) {
            // Nothing to report.
        }
    };

    void attemptFinished(GenerationAttempt outcome, Duration duration);

    /** @param sinceRequested from the creation of the document until it became AVAILABLE */
    void documentAvailable(Duration sinceRequested);
}
