package com.bhukkad.common.kafka;

import com.bhukkad.common.event.PlatformEventMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Publishes a {@link PlatformEventMessage} envelope to Kafka when the
 * platform is enabled and a {@link KafkaTemplate} is available (plan §6.2).
 * When gated off, the call is a no-op — the outbox poller ships the same
 * envelope instead.
 */
public class KafkaPlatformEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaPlatformEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final KafkaProperties properties;

    public KafkaPlatformEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                       KafkaProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
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
}