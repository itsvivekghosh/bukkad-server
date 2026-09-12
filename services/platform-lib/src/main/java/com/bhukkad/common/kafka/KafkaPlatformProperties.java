package com.bhukkad.common.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-service external event pipeline configuration, bound from
 * {@code app.events.external.*} (architecture-microservices-postgresql.md §6.2).
 *
 * <p>The Kafka transport is active only when {@code enabled} is {@code true}
 * AND {@code type} is {@code kafka}; {@code type: log} keeps the pipeline wired
 * for logging only. Kafka connection details live in the nested {@link Kafka}
 * record (relaxed kebab-case binding: {@code bootstrap-servers} etc.).</p>
 *
 * <p>CR-14 / W-2: {@code consumer-startup} controls whether bare
 * {@code @KafkaListener} containers auto-start when the backbone is enabled.
 * The default is {@code false} so a backbone-on service starts with consumers
 * paused until an operator explicitly starts them (staged waves).</p>
 *
 * @param enabled         master switch for the external event pipeline
 * @param type            transport type ({@code kafka} or {@code log})
 * @param kafka           Kafka connection and topic configuration
 * @param consumerStartup whether listeners auto-start on boot (default false)
 */
@ConfigurationProperties(prefix = "app.events.external")
public record KafkaPlatformProperties(boolean enabled, String type, Kafka kafka, boolean consumerStartup) {

    /** Kafka connection and topic settings. */
    public record Kafka(String bootstrapServers, String consumerGroup, String platformTopic, String dlqTopic) {
    }

    /** True only when the pipeline is enabled AND the transport is Kafka. */
    public boolean isKafkaEnabled() {
        return enabled && "kafka".equalsIgnoreCase(type);
    }

    /** Safe default representing a fully disabled external event pipeline. */
    public static KafkaPlatformProperties disabled() {
        return new KafkaPlatformProperties(false, "log", new Kafka("", "", "", ""), false);
    }
}
