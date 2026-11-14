package co.edu.corhuila.telemedia.documentgeneration.app;

import java.util.Map;
import java.util.Objects;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;

import co.edu.corhuila.telemedia.documentgeneration.adapter.in.background.BackgroundPdfGenerator;

/**
 * OBS-DG-06: pause switch of PDF generation, at {@code /actuator/generation} on the internal
 * management port only (never published, never routed by the gateway). {@code GET} reads the
 * state; {@code POST {"paused": true|false}} pauses or resumes without stopping the service.
 */
@Endpoint(id = "generation")
public class GenerationPauseEndpoint {

    private final BackgroundPdfGenerator generator;

    public GenerationPauseEndpoint(BackgroundPdfGenerator generator) {
        this.generator = Objects.requireNonNull(generator, "generator");
    }

    @ReadOperation
    public Map<String, Object> state() {
        return Map.of("paused", generator.isPaused());
    }

    @WriteOperation
    public Map<String, Object> setPaused(boolean paused) {
        if (paused) {
            generator.pause();
        } else {
            generator.resume();
        }
        return state();
    }
}
