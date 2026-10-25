package co.edu.corhuila.telemedia.documentgeneration.adapter.out.messaging;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import co.edu.corhuila.telemedia.documentgeneration.application.port.out.EventPublicationException;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.EventPublisher;
import co.edu.corhuila.telemedia.documentgeneration.application.port.out.PendingEvent;

/**
 * Publishes to RabbitMQ with the conventions of {@code communication-patterns.md} (2.1):
 * default exchange, a durable queue named after the event in {@code snake_case} with its
 * {@code .dlq}, {@code message_id} = event id. Messages are persistent and wait for the
 * broker's confirmation (DEC-008). The connection is opened on the first publication and
 * again after a failure, so the service starts without the broker.
 */
public final class RabbitEventPublisher implements EventPublisher, AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ConnectionFactory factory = new ConnectionFactory();
    private final Duration confirmTimeout;
    private Connection connection;
    private Channel channel;

    /** @param uri {@code amqp://user:password@host:port/vhost}; never logged */
    public RabbitEventPublisher(URI uri, Duration confirmTimeout) {
        this.confirmTimeout = Objects.requireNonNull(confirmTimeout, "confirmTimeout");
        try {
            factory.setUri(Objects.requireNonNull(uri, "uri"));
        } catch (Exception e) {
            throw new IllegalArgumentException("RABBITMQ_URL is not a valid amqp URI");
        }
        factory.setConnectionTimeout((int) confirmTimeout.toMillis());
        factory.setAutomaticRecoveryEnabled(false);
    }

    /** {@code ConsultationPdfGenerated} → {@code consultation_pdf_generated}. */
    static String queueOf(String eventType) {
        return eventType.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    @Override
    public synchronized void publish(PendingEvent event) {
        String queue = queueOf(event.eventType());
        try {
            Channel open = channel();
            open.queueDeclare(queue + ".dlq", true, false, false, null);
            open.queueDeclare(queue, true, false, false,
                    Map.of("x-dead-letter-exchange", "", "x-dead-letter-routing-key", queue + ".dlq"));
            AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                    .messageId(event.eventId().toString())
                    .type(event.eventType())
                    .correlationId(event.correlationId())
                    .contentType("application/json")
                    .deliveryMode(2)
                    .build();
            open.basicPublish("", queue, properties, envelope(event));
            open.waitForConfirmsOrDie(confirmTimeout.toMillis());
        } catch (IOException | TimeoutException | RuntimeException e) {
            reset();
            throw new EventPublicationException("RabbitMQ did not take the event: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            reset();
            throw new EventPublicationException("RabbitMQ publication interrupted");
        }
    }

    private Channel channel() throws IOException, TimeoutException {
        if (channel == null || !channel.isOpen()) {
            reset();
            connection = factory.newConnection("document-generation-api");
            channel = connection.createChannel();
            channel.confirmSelect();
        }
        return channel;
    }

    /** The ADR-011 envelope; the stored payload is embedded as it was written. */
    private static byte[] envelope(PendingEvent event) throws IOException {
        ObjectNode envelope = JSON.createObjectNode()
                .put("eventId", event.eventId().toString())
                .put("eventType", event.eventType())
                .put("occurredAt", event.occurredAt().toString())
                .put("correlationId", event.correlationId())
                .put("version", event.version());
        envelope.set("payload", JSON.readTree(event.payload()));
        return JSON.writeValueAsBytes(envelope);
    }

    private void reset() {
        try {
            if (connection != null) {
                connection.abort();
            }
        } finally {
            connection = null;
            channel = null;
        }
    }

    @Override
    public synchronized void close() {
        reset();
    }
}
