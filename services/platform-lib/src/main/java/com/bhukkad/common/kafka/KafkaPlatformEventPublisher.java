package com.bhukkad.common.kafka;

import com.bhukkad.common.event.PlatformEventMessage;
import lombok.Getter;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Publishes a {@link PlatformEventMessage} envelope to Kafka when the
 * platform is enabled and a {@link KafkaTemplate} is available (plan §6.2).
 * When gated off, the call is a no-op — the outbox poller ships the same
 * envelope instead.
 *
 * <p>Two publish modes:
 * <ul>
 *   <li>{@link #publish(PlatformEventMessage)} — fire and forget; the original
 *       send future is discarded. Preserved for backward compatibility.</li>
 *   <li>{@link #publishForResult(PlatformEventMessage)} — synchronous send that
 *       blocks up to {@code sendTimeout} for an acknowledgement so the outbox
 *       relay can mark a row PUBLISHED only on a confirmed ack, and FAILED on
 *       timeout/exception. This is what closes the reliability gap: an event
 *       is acknowledged durable on the broker side before the outbox row flips
 *       state.</li>
 * </ul>
 */
@Getter
public class KafkaPlatformEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaPlatformEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final KafkaProperties properties;
    private final Duration sendTimeout;

    public KafkaPlatformEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                       KafkaProperties properties) {
        this(kafkaTemplate, properties, Duration.ofSeconds(10));
    }

    public KafkaPlatformEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                       KafkaProperties properties,
                                       Duration sendTimeout) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.sendTimeout = sendTimeout;
    }

    public void publish(PlatformEventMessage message) {
        if (!properties.enabled()) {
            log.debug("KAFKA_DISABLED | eventType={} | eventId={}", message.eventType(), message.eventId());
            return;
        }
        String topic = properties.topicPrefix() + message.eventType().toLowerCase();
        try {
            kafkaTemplate.send(topic, message.aggregateId(), message.toJson());
            log.info("KAFKA_PUBLISHED | topic={} | eventId={} | eventType={}", topic, message.eventId(), message.eventType());
        } catch (Exception e) {
            log.error("KAFKA_PUBLISH_FAILED | topic={} | eventId={} | eventType={}",
                    topic, message.eventId(), message.eventType(), e);
        }
    }

    /**
     * Synchronous, acknowledged publish for the outbox relay.
     *
     * @return {@code true} if the record was acknowledged by the broker within
     *         {@link #getSendTimeout()}, or if the publisher is disabled (a
     *         no-op success — local/dev runs without a broker), so the calling
     *         outbox row can flip to a terminal state; {@code false} on
     *         timeout/error so the caller keeps the row re-queueable.
     */
    public boolean publishForResult(PlatformEventMessage message) {
        if (!properties.enabled()) {
            log.debug("KAFKA_DISABLED | eventType={} | eventId={}", message.eventType(), message.eventId());
            return true;
        }
        String topic = properties.topicPrefix() + message.eventType().toLowerCase();
        try {
            CompletableFuture<SendResult<String, String>> future =
                    kafkaTemplate.send(topic, message.aggregateId(), message.toJson());
            SendResult<String, String> result = future
                    .get(sendTimeout.getSeconds(), TimeUnit.SECONDS);
            RecordMetadata meta = result.getRecordMetadata();
            log.info("KAFKA_PUBLISHED | topic={} | eventId={} | eventType={} | offset={} | partition={}",
                    topic, message.eventId(), message.eventType(), meta.offset(), meta.partition());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("KAFKA_PUBLISH_INTERRUPTED | topic={} | eventId={} | eventType={}",
                    topic, message.eventId(), message.eventType());
            return false;
        } catch (Exception e) {
            log.error("KAFKA_PUBLISH_FAILED | topic={} | eventId={} | eventType={}",
                    topic, message.eventId(), message.eventType(), e);
            return false;
        }
    }
}
