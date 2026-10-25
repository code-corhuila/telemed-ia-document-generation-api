package co.edu.corhuila.telemedia.documentgeneration.adapter.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;

import co.edu.corhuila.telemedia.documentgeneration.application.port.out.EventPublicationException;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.PendingEvent;

/**
 * DEC-008 and the conventions of {@code communication-patterns.md} (2.1) against a real
 * RabbitMQ. Skipped without TEST_RABBITMQ_URL; the queues are deleted before each test.
 */
@EnabledIfEnvironmentVariable(named = "TEST_RABBITMQ_URL", matches = ".+")
class RabbitEventPublisherIntegrationTest {

    private static final String QUEUE = "consultation_pdf_generated";
    private static final ObjectMapper JSON = new ObjectMapper();

    private Connection inspection;
    private Channel channel;
    private RabbitEventPublisher publisher;

    @BeforeEach
    void start() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setUri(System.getenv("TEST_RABBITMQ_URL"));
        inspection = factory.newConnection();
        channel = inspection.createChannel();
        channel.queueDelete(QUEUE);
        channel.queueDelete(QUEUE + ".dlq");
        publisher = new RabbitEventPublisher(URI.create(System.getenv("TEST_RABBITMQ_URL")), Duration.ofSeconds(5));
    }

    @AfterEach
    void stop() throws Exception {
        publisher.close();
        inspection.close();
    }

    private static PendingEvent event() {
        return new PendingEvent(UUID.randomUUID(), "ConsultationPdfGenerated", 1, "trace-relay",
                "{\"documentId\":\"6f178b1a-9c0d-4c23-96ad-1e8da3b2ef6d\",\"summaryId\":\"s\"}",
                Instant.parse("2026-10-25T10:00:00.123456Z"));
    }

    @Test
    void publishesTheEnvelopeToTheEventQueueAsAPersistentMessage() throws Exception {
        PendingEvent event = event();

        publisher.publish(event);

        GetResponse message = channel.basicGet(QUEUE, true);
        assertThat(message).isNotNull();
        assertThat(message.getProps().getMessageId()).isEqualTo(event.eventId().toString());
        assertThat(message.getProps().getContentType()).isEqualTo("application/json");
        assertThat(message.getProps().getDeliveryMode()).isEqualTo(2);
        assertThat(message.getProps().getType()).isEqualTo("ConsultationPdfGenerated");
        JsonNode body = JSON.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
        assertThat(body.get("eventId").asText()).isEqualTo(event.eventId().toString());
        assertThat(body.get("eventType").asText()).isEqualTo("ConsultationPdfGenerated");
        assertThat(body.get("occurredAt").asText()).isEqualTo("2026-10-25T10:00:00.123456Z");
        assertThat(body.get("correlationId").asText()).isEqualTo("trace-relay");
        assertThat(body.get("version").asInt()).isEqualTo(1);
        assertThat(body.get("payload").get("documentId").asText()).isEqualTo("6f178b1a-9c0d-4c23-96ad-1e8da3b2ef6d");
    }

    @Test
    void declaresTheQueueAndItsDeadLetterQueueWithTheConventionArguments() throws Exception {
        publisher.publish(event());

        // The same declaration is accepted; any other one is refused with 406.
        channel.queueDeclare(QUEUE, true, false, false,
                Map.of("x-dead-letter-exchange", "", "x-dead-letter-routing-key", QUEUE + ".dlq"));
        channel.queueDeclarePassive(QUEUE + ".dlq");
        Channel other = inspection.createChannel();
        assertThatThrownBy(() -> other.queueDeclare(QUEUE, true, false, false, null)).isInstanceOf(IOException.class);
    }

    @Test
    void theQueueIsTheEventNameInSnakeCase() {
        assertThat(RabbitEventPublisher.queueOf("ConsultationPdfGenerated")).isEqualTo(QUEUE);
        assertThat(RabbitEventPublisher.queueOf("PostConsultationSummaryGenerated"))
                .isEqualTo("post_consultation_summary_generated");
    }

    @Test
    void anUnreachableBrokerIsAPublicationFailureAndALaterCallReconnects() throws Exception {
        try (RabbitEventPublisher unreachable = new RabbitEventPublisher(URI.create("amqp://127.0.0.1:1"),
                Duration.ofSeconds(2))) {
            assertThatThrownBy(() -> unreachable.publish(event()))
                    .isInstanceOf(EventPublicationException.class)
                    .hasMessageContaining("RabbitMQ");
        }
        publisher.publish(event());
        publisher.publish(event());
        assertThat(channel.messageCount(QUEUE)).isEqualTo(2);
    }
}
