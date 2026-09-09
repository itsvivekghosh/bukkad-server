package com.bhukkad.common.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration for the per-service outbox poller/relay.
 *
 * <p>Bound from {@code app.events.external.kafka.outbox.*}. The relay only
 * exists when the external event pipeline is fully enabled
 * ({@code app.events.external.enabled=true} AND
 * {@code app.events.external.type=kafka}) — the relay beans and the Kafka
 * publisher share the single {@code ExternalEventsProperties} gate
 * (PERF-2/B2: one gate, one behaviour).</p>
 *
 * <p>No service {@code application.yml} currently carries the {@code outbox.*}
 * block, so unset values ({@code 0}/{@code null}) are normalised to the safe
 * defaults below by the compact constructor — the relay works out of the box
 * instead of silently no-op'ing on a zero {@code batchSize}.</p>
 *
 * @param batchSize           rows claimed per poll cycle
 * @param pollInterval        delay between poll cycles
 * @param processingTimeout   a row in PROCESSING older than now - timeout is
 *                            treated as abandoned and re-queued to PENDING;
 *                            also bounds the retry backoff and drives the
 *                            scheduled {@code recoverStale} sweep
 * @param sendTimeout         per-message Kafka send timeout passed to the relay
 *                            by {@link com.bhukkad.common.kafka.KafkaPlatformEventPublisher}
 * @param maxErrorLength      truncation length for the stored {@code lastError}
 *                            string on a failed publish
 * @param maxRetries          publish attempts before a row is copied to
 *                            {@code dead_letter_events} and marked FAILED
 *                            (PERF-2: wires the previously dead knob —
 *                            {@code retryCount} was bumped but never compared)
 * @param retryBackoff        base exponential backoff applied via
 *                            {@code next_attempt_at} after a failed publish
 *                            (attempt n waits {@code retryBackoff * 2^(n-1)},
 *                            capped at {@link #processingTimeout})
 */
@ConfigurationProperties(prefix = "app.events.external.kafka.outbox")
public record OutboxProperties(
        int batchSize,
        Duration pollInterval,
        Duration processingTimeout,
        Duration sendTimeout,
        int maxErrorLength,
        int maxRetries,
        Duration retryBackoff
) {

    private static final int DEFAULT_BATCH_SIZE = 100;
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(5);
    private static final Duration DEFAULT_PROCESSING_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_SEND_TIMEOUT = Duration.ofSeconds(10);
    private static final int DEFAULT_MAX_ERROR_LENGTH = 1000;
    private static final int DEFAULT_MAX_RETRIES = 5;
    private static final Duration DEFAULT_RETRY_BACKOFF = Duration.ofSeconds(2);

    public OutboxProperties {
        if (batchSize <= 0) {
            batchSize = DEFAULT_BATCH_SIZE;
        }
        if (pollInterval == null || pollInterval.isZero() || pollInterval.isNegative()) {
            pollInterval = DEFAULT_POLL_INTERVAL;
        }
        if (processingTimeout == null || processingTimeout.isZero() || processingTimeout.isNegative()) {
            processingTimeout = DEFAULT_PROCESSING_TIMEOUT;
        }
        if (sendTimeout == null || sendTimeout.isZero() || sendTimeout.isNegative()) {
            sendTimeout = DEFAULT_SEND_TIMEOUT;
        }
        if (maxErrorLength <= 0) {
            maxErrorLength = DEFAULT_MAX_ERROR_LENGTH;
        }
        if (maxRetries <= 0) {
            maxRetries = DEFAULT_MAX_RETRIES;
        }
        if (retryBackoff == null || retryBackoff.isZero() || retryBackoff.isNegative()) {
            retryBackoff = DEFAULT_RETRY_BACKOFF;
        }
    }

    public static OutboxProperties defaults() {
        return new OutboxProperties(
                DEFAULT_BATCH_SIZE,
                DEFAULT_POLL_INTERVAL,
                DEFAULT_PROCESSING_TIMEOUT,
                DEFAULT_SEND_TIMEOUT,
                DEFAULT_MAX_ERROR_LENGTH,
                DEFAULT_MAX_RETRIES,
                DEFAULT_RETRY_BACKOFF);
    }

    /**
     * Retained for the legacy call sites; with the compact-constructor
     * normalisation this is always {@code true} — runtime enablement is now
     * solely the bean gate (relay + publisher share one {@code @Conditional}
     * expression, PERF-2/B2).
     */
    public boolean isEnabled() {
        return batchSize > 0 && !pollInterval.isZero();
    }

    /** Exponential backoff for the row's next attempt after {@code failedAttempts} failures. */
    public Duration backoffFor(int failedAttempts) {
        int exponent = Math.min(Math.max(failedAttempts - 1, 0), 16);
        Duration backoff = retryBackoff.multipliedBy(1L << exponent);
        return backoff.compareTo(processingTimeout) > 0 ? processingTimeout : backoff;
    }
}
