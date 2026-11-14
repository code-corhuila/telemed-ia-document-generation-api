package co.edu.corhuila.telemedia.documentgeneration.adapter.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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

import co.edu.corhuila.telemedia.documentgeneration.adapter.out.messaging.RabbitEventPublisher.Settings;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.EventPublicationException;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.PendingEvent;

/**
 * DEC-009 and the common event contract of {@code communication-patterns.md} (2.1) against a
 * real RabbitMQ. The test plays the consumer: it owns the queue and binds it. Skipped without
 * TEST_RABBITMQ_URL; the exchange and the queue are deleted before each test.
 */
@EnabledIfEnvironmentVariable(named = "TEST_RABBITMQ_URL", matches = ".+")
class RabbitEventPublisherIntegrationTest {

    private static final String EXCHANGE = "documents.events";
    private static final String ROUTING_KEY = "consultation-pdf-generated";
    private static final String CONSUMER_QUEUE = "notifications.consultation-pdf-generated";
    private static final ObjectMapper JSON = new ObjectMapper();

    private Connection inspection;
    private Channel channel;
    private RabbitEventPublisher publisher;

    static Settings settings(URI uri, Duration timeout) {
        String[] user = uri.getUserInfo() == null ? new String[] {"guest", "guest"} : uri.getUserInfo().split(":", 2);
        return new Settings(uri.getHost(), uri.getPort() == -1 ? 5672 : uri.getPort(), user[0], user[1], timeout);
    }

    @BeforeEach
    void start() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setUri(System.getenv("TEST_RABBITMQ_URL"));
        inspection = factory.newConnection();
        channel = inspection.createChannel();
        channel.queueDelete(CONSUMER_QUEUE);
        channel.exchangeDelete(EXCHANGE);
        publisher = new RabbitEventPublisher(settings(URI.create(System.getenv("TEST_RABBITMQ_URL")),
                Duration.ofSeconds(5)));
    }

    @AfterEach
    void stop() throws Exception {
        publisher.close();
        inspection.close();
    }

    private void bindConsumerQueue() throws IOException {
        channel.exchangeDeclare(EXCHANGE, "topic", true);
        channel.queueDeclare(CONSUMER_QUEUE, true, false, false, null);
        channel.queueBind(CONSUMER_QUEUE, EXCHANGE, ROUTING_KEY);
    }

    private static PendingEvent event() {
        return new PendingEvent(UUID.randomUUID(), "ConsultationPdfGenerated", 1, "trace-relay",
                "{\"documentId\":\"6f178b1a-9c0d-4c23-96ad-1e8da3b2ef6d\",\"appointmentId\":\"a\"}",
                Instant.parse("2026-10-25T10:00:00.123456Z"));
    }

    @Test
    void publishesTheEnvelopeToTheDomainExchangeWithTheEventRoutingKey() throws Exception {
        bindConsumerQueue();
        PendingEvent event = event();

        publisher.publish(event);

        GetResponse message = channel.basicGet(CONSUMER_QUEUE, true);
        assertThat(message).isNotNull();
        assertThat(message.getEnvelope().getExchange()).isEqualTo(EXCHANGE);
        assertThat(message.getEnvelope().getRoutingKey()).isEqualTo(ROUTING_KEY);
        assertThat(message.getProps().getMessageId()).isEqualTo(event.eventId().toString());
        assertThat(message.getProps().getCorrelationId()).isEqualTo("trace-relay");
        assertThat(message.getProps().getType()).isEqualTo("ConsultationPdfGenerated");
        assertThat(message.getProps().getContentType()).isEqualTo("application/json");
        assertThat(message.getProps().getDeliveryMode()).isEqualTo(2);
        JsonNode body = JSON.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
        assertThat(body.get("eventId").asText()).isEqualTo(event.eventId().toString());
        assertThat(body.get("eventType").asText()).isEqualTo("ConsultationPdfGenerated");
        assertThat(body.get("occurredAt").asText()).isEqualTo("2026-10-25T10:00:00.123456Z");
        assertThat(body.get("correlationId").asText()).isEqualTo("trace-relay");
        assertThat(body.get("version").asInt()).isEqualTo(1);
        assertThat(body.get("payload").get("documentId").asText()).isEqualTo("6f178b1a-9c0d-4c23-96ad-1e8da3b2ef6d");
    }

    @Test
    void anEventNoQueueTakesIsAFailedPublicationAndNoQueueIsCreated() throws Exception {
        assertThatThrownBy(() -> publisher.publish(event()))
                .isInstanceOf(EventPublicationException.class)
                .hasMessageContaining("no queue");

        // Only the exchange exists: the consumers own their queues.
        channel.exchangeDeclarePassive(EXCHANGE);
        Channel other = inspection.createChannel();
        assertThatThrownBy(() -> other.queueDeclarePassive("consultation_pdf_generated")).isInstanceOf(IOException.class);
    }

    @Test
    void declaresTheExchangeAsADurableTopic() throws Exception {
        bindConsumerQueue();
        publisher.publish(event());

        Channel other = inspection.createChannel();
        assertThatThrownBy(() -> other.exchangeDeclare(EXCHANGE, "direct", true)).isInstanceOf(IOException.class);
        channel.exchangeDeclare(EXCHANGE, "topic", true);
    }

    @Test
    void theRoutingKeyIsTheEventNameInKebabCase() {
        assertThat(RabbitEventPublisher.routingKeyOf("ConsultationPdfGenerated")).isEqualTo(ROUTING_KEY);
        assertThat(RabbitEventPublisher.routingKeyOf("PostConsultationSummaryGenerated"))
                .isEqualTo("post-consultation-summary-generated");
    }

    @Test
    void anUnreachableBrokerIsAPublicationFailureAndALaterCallReconnects() throws Exception {
        try (RabbitEventPublisher unreachable = new RabbitEventPublisher(
                new Settings("127.0.0.1", 1, "guest", "guest", Duration.ofSeconds(2)))) {
            assertThatThrownBy(() -> unreachable.publish(event()))
                    .isInstanceOf(EventPublicationException.class)
                    .hasMessageContaining("RabbitMQ");
        }
        bindConsumerQueue();
        publisher.publish(event());
        publisher.publish(event());
        assertThat(channel.messageCount(CONSUMER_QUEUE)).isEqualTo(2);
    }

    @Test
    void theSettingsNeverPrintThePassword() {
        assertThat(new Settings("rabbitmq", 5672, "document-generation", "broker-password-0001",
                Duration.ofSeconds(5)).toString()).doesNotContain("broker-password-0001");
    }
}
