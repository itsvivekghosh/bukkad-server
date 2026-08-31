package com.bhukkad.common.kafka;

/**
 * Kafka configuration properties for the platform publisher/consumer.
 *
 * @param enabled     master switch for the Kafka publisher (gated by
 *                    {@code external-events.enabled} env var)
 * @param topicPrefix prefix applied to every event type (e.g.
 *                    {@code bhukkad.}) when building the topic name
 * @param groupId     consumer group id for the platform consumer
 */
public record KafkaProperties(boolean enabled, String topicPrefix, String groupId) {

    public static KafkaProperties disabled() {
        return new KafkaProperties(false, "", "");
    }
}