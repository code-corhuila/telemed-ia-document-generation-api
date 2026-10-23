package co.edu.corhuila.telemedia.documentgeneration.adapter.out.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import co.edu.corhuila.telemedia.documentgeneration.application.port.in.GenerationAttempt;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Golden signals of the PDF generation (week 11 workshop, NFR-005): traffic and errors by
 * outcome, latency with the SLO buckets, and the freshness of the asynchronous flow.
 */
class MicrometerGenerationMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MicrometerGenerationMetrics metrics = new MicrometerGenerationMetrics(registry);

    private Timer attempts(String outcome) {
        return registry.get(MicrometerGenerationMetrics.ATTEMPTS).tag("outcome", outcome).timer();
    }

    private static double countUpTo(Timer timer, double seconds) {
        return Arrays.stream(timer.takeSnapshot().histogramCounts())
                .filter(bucket -> bucket.bucket(TimeUnit.SECONDS) == seconds)
                .mapToDouble(CountAtBucket::count)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void attemptsAreCountedByOutcome() {
        metrics.attemptFinished(GenerationAttempt.AVAILABLE, Duration.ofMillis(800));
        metrics.attemptFinished(GenerationAttempt.AVAILABLE, Duration.ofMillis(900));
        metrics.attemptFinished(GenerationAttempt.RETRY, Duration.ofMillis(100));
        metrics.attemptFinished(GenerationAttempt.FAILED, Duration.ofMillis(100));

        assertThat(attempts("available").count()).isEqualTo(2);
        assertThat(attempts("retry").count()).isEqualTo(1);
        assertThat(attempts("failed").count()).isEqualTo(1);
    }

    @Test
    void attemptDurationsUseTheSloBuckets() {
        metrics.attemptFinished(GenerationAttempt.AVAILABLE, Duration.ofMillis(1500));
        metrics.attemptFinished(GenerationAttempt.AVAILABLE, Duration.ofSeconds(3));

        Timer timer = attempts("available");
        assertThat(Arrays.stream(timer.takeSnapshot().histogramCounts()).map(b -> b.bucket(TimeUnit.SECONDS)))
                .containsExactly(0.1, 0.5, 1.0, 2.0, 5.0);
        assertThat(countUpTo(timer, 2.0)).isEqualTo(1);
        assertThat(countUpTo(timer, 5.0)).isEqualTo(2);
    }

    @Test
    void freshnessMeasuresTheWaitUntilThePdfIsAvailable() {
        metrics.documentAvailable(Duration.ofSeconds(40));
        metrics.documentAvailable(Duration.ofSeconds(90));

        Timer freshness = registry.get(MicrometerGenerationMetrics.TIME_TO_AVAILABLE).timer();
        assertThat(freshness.count()).isEqualTo(2);
        assertThat(countUpTo(freshness, 60.0)).isEqualTo(1);
    }

    @Test
    void noMeterCarriesAnIdentifier() {
        metrics.attemptFinished(GenerationAttempt.AVAILABLE, Duration.ofSeconds(1));
        metrics.documentAvailable(Duration.ofSeconds(1));

        assertThat(registry.getMeters()).flatExtracting(meter -> meter.getId().getTags())
                .extracting(tag -> tag.getKey())
                .containsOnly("outcome");
        assertThat(registry.getMeters()).extracting(Meter::getId).extracting(Meter.Id::getName)
                .allMatch(name -> name.startsWith("document.generation."));
    }
}
