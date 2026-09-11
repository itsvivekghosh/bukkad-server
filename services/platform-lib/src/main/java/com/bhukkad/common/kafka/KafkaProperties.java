package com.bhukkad.common.kafka;

/**
 * Kafka configuration properties for the platform publisher/consumer.
 *
 * @param enabled master switch for the Kafka publisher (gated by
 *                {@code external-events.enabled} env var)
 * @param topic   the single base platform topic every event of this service is
 *                published to (e.g. {@code order.events.v1}); consumers
 *                subscribe to the same base topic and dispatch on the
 *                {@code eventType} carried inside the envelope
 * @param groupId consumer group id for the platform consumer
 */
public record KafkaProperties(boolean enabled, String topic, String groupId) {

    public static KafkaProperties disabled() {
        return new KafkaProperties(false, "", "");
    }
}