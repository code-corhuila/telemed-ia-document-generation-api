package co.edu.corhuila.telemedia.documentgeneration.adapter.out.metrics;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import co.edu.corhuila.telemedia.documentgeneration.application.port.in.GenerationAttempt;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.GenerationMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Generation metrics for Prometheus (NFR-005, week 11 SLOs):
 * <ul>
 *   <li>{@code document_generation_attempts_seconds}: one timer per outcome, so its
 *       {@code _count} is the traffic and the errors, and its buckets (0.1 to 5 s) give the
 *       latency SLI ("95% of attempts under 2 s");</li>
 *   <li>{@code document_generation_time_to_available_seconds}: from the request until the
 *       PDF is AVAILABLE, the freshness SLI ("95% within 60 s").</li>
 * </ul>
 * The only label is the outcome: identifiers would create a time series per document.
 */
public final class MicrometerGenerationMetrics implements GenerationMetrics {

    static final String ATTEMPTS = "document.generation.attempts";
    static final String TIME_TO_AVAILABLE = "document.generation.time.to.available";

    private final Map<GenerationAttempt, Timer> attempts = new EnumMap<>(GenerationAttempt.class);
    private final Timer timeToAvailable;

    public MicrometerGenerationMetrics(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        for (GenerationAttempt outcome : GenerationAttempt.values()) {
            attempts.put(outcome, Timer.builder(ATTEMPTS)
                    .description("PDF generation attempts by outcome")
                    .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                    .serviceLevelObjectives(Duration.ofMillis(100), Duration.ofMillis(500), Duration.ofSeconds(1),
                            Duration.ofSeconds(2), Duration.ofSeconds(5))
                    .register(registry));
        }
        this.timeToAvailable = Timer.builder(TIME_TO_AVAILABLE)
                .description("Time from the request until the PDF is available")
                .serviceLevelObjectives(Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(30),
                        Duration.ofSeconds(60), Duration.ofSeconds(120), Duration.ofSeconds(300))
                .register(registry);
    }

    @Override
    public void attemptFinished(GenerationAttempt outcome, Duration duration) {
        attempts.get(Objects.requireNonNull(outcome, "outcome")).record(duration);
    }

    @Override
    public void documentAvailable(Duration sinceRequested) {
        timeToAvailable.record(sinceRequested);
    }
}
