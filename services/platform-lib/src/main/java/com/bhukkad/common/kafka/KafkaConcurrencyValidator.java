package com.bhukkad.common.kafka;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/**
 * Validates Kafka consumer concurrency against actual topic partition counts
 * at startup. Logs a warning when the configured concurrency exceeds the
 * available partitions for the platform topic.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KafkaPlatformProperties.class)
public class KafkaConcurrencyValidator {

    private final KafkaConcurrencyGuard guard;
    private final KafkaPlatformProperties properties;

    public KafkaConcurrencyValidator(KafkaAdmin kafkaAdmin, KafkaPlatformProperties properties) {
        this.guard = new KafkaConcurrencyGuard(kafkaAdmin);
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void validate() {
        if (properties == null || !properties.isKafkaEnabled()) {
            return;
        }
        int concurrency = Math.max(1, properties.kafka().listenerConcurrency());
        guard.validate(properties.kafka().platformTopic(), concurrency);
        if (properties.kafka().topicConcurrency() != null) {
            for (var entry : properties.kafka().topicConcurrency().entrySet()) {
                guard.validate(entry.getKey(), entry.getValue());
            }
        }
    }
}
