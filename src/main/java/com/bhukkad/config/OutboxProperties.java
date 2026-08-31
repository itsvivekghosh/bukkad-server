package com.bhukkad.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbox polling and delivery reliability settings.
 */
@Data
@ConfigurationProperties(prefix = "app.outbox")
public class OutboxProperties {

    /** Maximum number of publish attempts before an event is dead-lettered. */
    private int maxRetries = 5;

    /** Number of events fetched per sweep — raised for heavy traffic (100/s at 1s poll). */
    private int batchSize = 100;

    /** Maximum number of events fetched per dead-letter sweep. */
    private int deadLetterBatchSize = 50;

    /**
     * Age after which an event stuck in PROCESSING (claimed by a sweep that
     * crashed or was killed) is considered abandoned and reset to PENDING.
     */
    private long staleProcessingAfterMs = 600_000;

    /** Interval of the recovery sweep that resets stale PROCESSING events. */
    private long recoveryIntervalMs = 60_000;
}
