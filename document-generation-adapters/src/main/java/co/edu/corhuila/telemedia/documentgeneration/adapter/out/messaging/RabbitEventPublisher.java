package co.edu.corhuila.telemedia.documentgeneration.adapter.out.messaging;

import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 * Publishes to RabbitMQ with the common event contract of {@code communication-patterns.md}
 * (2.1, DEC-009): the durable topic exchange {@code documents.events}, routing key = event
 * name in kebab case, {@code message_id} = event id, persistent, publisher confirms. It
 * declares no queue: consumers own theirs. Messages are {@code mandatory}, so an event that
 * no queue takes is returned and counts as not published. The connection is opened on the
 * first publication and again after a failure, so the service starts without the broker.
 */
public final class RabbitEventPublisher implements EventPublisher, AutoCloseable {

    static final String EXCHANGE = "documents.events";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** @param password from the environment, never versioned; left out of {@link #toString()} */
    public record Settings(String host, int port, String username, String password, Duration confirmTimeout) {

        public Settings {
            Objects.requireNonNull(host, "host");
            Objects.requireNonNull(username, "username");
            Objects.requireNonNull(password, "password");
            Objects.requireNonNull(confirmTimeout, "confirmTimeout");
            if (port < 1 || !confirmTimeout.isPositive()) {
                throw new IllegalArgumentException("the broker port and the confirmation timeout must be positive");
            }
        }

        @Override
        public String toString() {
            return "Settings[host=" + host + ", port=" + port + ", username=" + username + ", password=***"
                    + ", confirmTimeout=" + confirmTimeout + "]";
        }
    }

    private final ConnectionFactory factory = new ConnectionFactory();
    private final Duration confirmTimeout;
    /** Message ids the broker returned because no queue took them. */
    private final Set<String> returned = ConcurrentHashMap.newKeySet();
    private Connection connection;
    private Channel channel;

    public RabbitEventPublisher(Settings settings) {
        this.confirmTimeout = settings.confirmTimeout();
        factory.setHost(settings.host());
        factory.setPort(settings.port());
        factory.setUsername(settings.username());
        factory.setPassword(settings.password());
        factory.setConnectionTimeout((int) confirmTimeout.toMillis());
        factory.setAutomaticRecoveryEnabled(false);
    }

    /** {@code ConsultationPdfGenerated} → {@code consultation-pdf-generated}. */
    static String routingKeyOf(String eventType) {
        return eventType.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }

    @Override
    public synchronized void publish(PendingEvent event) {
        String messageId = event.eventId().toString();
        try {
            Channel open = channel();
            AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                    .messageId(messageId)
                    .type(event.eventType())
                    .correlationId(event.correlationId())
                    .contentType("application/json")
                    .deliveryMode(2)
                    .build();
            open.basicPublish(EXCHANGE, routingKeyOf(event.eventType()), true, properties, envelope(event));
            // A return always arrives before the confirmation of the same message.
            open.waitForConfirmsOrDie(confirmTimeout.toMillis());
        } catch (IOException | TimeoutException | RuntimeException e) {
            reset();
            throw new EventPublicationException("RabbitMQ did not take the event: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            reset();
            throw new EventPublicationException("RabbitMQ publication interrupted");
        }
        if (returned.remove(messageId)) {
            throw new EventPublicationException("no queue is bound to " + EXCHANGE + " for " + event.eventType());
        }
    }

    private Channel channel() throws IOException, TimeoutException {
        if (channel == null || !channel.isOpen()) {
            reset();
            connection = factory.newConnection("document-generation-api");
            channel = connection.createChannel();
            channel.confirmSelect();
            channel.exchangeDeclare(EXCHANGE, "topic", true);
            channel.addReturnListener(message -> returned.add(message.getProperties().getMessageId()));
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
        returned.clear();
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
