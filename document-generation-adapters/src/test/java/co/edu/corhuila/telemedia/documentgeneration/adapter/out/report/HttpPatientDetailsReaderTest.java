package co.edu.corhuila.telemedia.documentgeneration.adapter.out.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import co.edu.corhuila.telemedia.documentgeneration.adapter.out.identity.ServiceTokens;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.ConsultationSummary;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.PatientDetails;

/** DEC-015: names from identity (auth DEC-014) and the reason from the agent (agent DEC-019). */
class HttpPatientDetailsReaderTest {

    private static final UUID PATIENT = UUID.fromString("b0000000-0000-0000-0000-000000000001");
    private static final UUID PROFESSIONAL = UUID.fromString("c0000000-0000-0000-0000-000000000002");
    private static final UUID APPOINTMENT = UUID.fromString("a0000000-0000-0000-0000-000000000001");
    private static final String NAMES = "/internal/account-names";
    private static final String REPORT = "/internal/preconsultation-reports";
    private static final String TRACE = "trace-details-1";

    private HttpServer server;
    private final Map<String, String[]> answers = new ConcurrentHashMap<>();
    private final List<String> queries = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final List<String> correlations = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            queries.add(exchange.getRequestURI().getPath() + "?" + exchange.getRequestURI().getQuery());
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            correlations.add(exchange.getRequestHeaders().getFirst("X-Correlation-Id"));
            String[] answer = answers.getOrDefault(exchange.getRequestURI().getPath(),
                    new String[] {"404", "{\"error\":\"NOT_FOUND\"}"});
            byte[] body = answer[1].getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(Integer.parseInt(answer[0]), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private HttpPatientDetailsReader reader(boolean withAgent) {
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        return new HttpPatientDetailsReader(HttpClient.newHttpClient(),
                new HttpPatientDetailsReader.Settings(base, withAgent ? base : null, Duration.ofSeconds(2)),
                new FixedToken());
    }

    private static ConsultationSummary summary(UUID professionalId) {
        return new ConsultationSummary(UUID.randomUUID(), UUID.randomUUID(), APPOINTMENT, PATIENT, null, "Reposo",
                List.of(), null, null, Map.of(), professionalId, null);
    }

    private void namesAnswer() {
        answers.put(NAMES, new String[] {"200", """
                {"data": [{"id": "%s", "fullName": "Laura Méndez"},
                          {"id": "%s", "fullName": "Dra. Camila Rojas"}]}
                """.formatted(PATIENT, PROFESSIONAL)});
    }

    @Test
    void readsBothNamesAndTheReasonWithTheServiceToken() {
        namesAnswer();
        answers.put(REPORT, new String[] {"200", """
                {"data": {"appointmentId": "%s", "reasonForVisit": "Nos contaste que te duele la cabeza.",
                          "source": "model"}}
                """.formatted(APPOINTMENT)});

        PatientDetails details = reader(true).read(summary(PROFESSIONAL), TRACE);

        assertThat(details).isEqualTo(
                new PatientDetails("Laura Méndez", "Dra. Camila Rojas", "Nos contaste que te duele la cabeza."));
        assertThat(queries).contains(NAMES + "?ids=" + PATIENT + "," + PROFESSIONAL,
                REPORT + "?appointmentId=" + APPOINTMENT);
        assertThat(authorizations).containsOnly("Bearer service-token");
        assertThat(correlations).containsOnly(TRACE);
    }

    @Test
    void whatCannotBeReadIsLeftEmpty() {
        answers.put(NAMES, new String[] {"500", "{}"});

        PatientDetails details = reader(true).read(summary(PROFESSIONAL), TRACE);

        assertThat(details).isEqualTo(PatientDetails.NONE);
    }

    @Test
    void withoutTheProfessionalOnlyThePatientIsAsked() {
        namesAnswer();

        PatientDetails details = reader(true).read(summary(null), TRACE);

        assertThat(details.patientName()).isEqualTo("Laura Méndez");
        assertThat(details.professionalName()).isNull();
        assertThat(queries).contains(NAMES + "?ids=" + PATIENT);
    }

    @Test
    void withoutTheAgentConfiguredTheReasonIsNotAsked() {
        namesAnswer();

        assertThat(reader(false).read(summary(PROFESSIONAL), TRACE).reasonForVisit()).isNull();
        assertThat(queries).noneMatch(query -> query.startsWith(REPORT));
    }

    @Test
    void anUnreachableServiceIsLeftEmpty() {
        server.stop(0);

        assertThat(reader(true).read(summary(PROFESSIONAL), TRACE)).isEqualTo(PatientDetails.NONE);
    }

    private static final class FixedToken implements ServiceTokens {

        @Override
        public String token() {
            return "service-token";
        }

        @Override
        public void invalidate(String rejected) {
        }
    }
}
