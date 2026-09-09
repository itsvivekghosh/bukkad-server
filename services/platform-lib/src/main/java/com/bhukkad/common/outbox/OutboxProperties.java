package com.bhukkad.common.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration for the per-service outbox poller/relay.
 *
 * <p>Bound from {@code app.events.external.kafka.outbox.*}. Only consulted when
 * the external event pipeline is enabled
 * ({@code app.events.external.enabled=true}); the poller bean itself is
 * {@code @ConditionalOnProperty}-gated so it has no scheduler footprint when
 * Kafka is off.</p>
 *
 * @param batchSize           rows claimed per poll cycle
 * @param pollInterval        delay between poll cycles
 * @param processingTimeout   a row in PROCESSING older than now - timeout is
 *                            treated as abandoned and re-queued to PENDING
 * @param sendTimeout         per-message Kafka send timeout passed to the relay
 *                            by {@link com.bhukkad.common.kafka.KafkaPlatformEventPublisher}
 * @param maxErrorLength      truncation length for the stored {@code lastError}
 *                            string on a failed publish
 */
@ConfigurationProperties(prefix = "app.events.external.kafka.outbox")
public record OutboxProperties(
        int batchSize,
        Duration pollInterval,
        Duration processingTimeout,
        Duration sendTimeout,
        int maxErrorLength
) {

    public static OutboxProperties defaults() {
        return new OutboxProperties(
                100,
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                Duration.ofSeconds(10),
                1000);
    }

    /** True only when a batch can actually be drained (Kafka must be reachable). */
    public boolean isEnabled() {
        return batchSize > 0 && !pollInterval.isZero();
    }
}
