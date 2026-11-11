package co.edu.corhuila.telemedia.documentgeneration.adapter.out.report;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

import co.edu.corhuila.telemedia.documentgeneration.adapter.out.identity.ServiceTokens;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.ConsultationSummary;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.PatientDetails;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.PatientDetailsReader;

public final class HttpPatientDetailsReader implements PatientDetailsReader {

    /** @param agentBaseUrl {@code null} when the agent is not configured: no reason is read */
    public record Settings(URI identityBaseUrl, URI agentBaseUrl, Duration requestTimeout) {
    }

    public HttpPatientDetailsReader(HttpClient http, Settings settings, ServiceTokens tokens) {
    }

    @Override
    public PatientDetails read(ConsultationSummary summary, String correlationId) {
        return PatientDetails.NONE;
    }
}
