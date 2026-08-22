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

    /** Number of events fetched per sweep. */
    private int batchSize = 50;

    /** Maximum number of events fetched per dead-letter sweep. */
    private int deadLetterBatchSize = 50;
}
