package com.bhukkad.common.kafka.dlq;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DLQ-specific configuration properties.
 */
@ConfigurationProperties(prefix = "app.events.external.dlq")
public record DlqProperties(
        /** Enable DLQ consumer and management endpoints. Default: false. */
        boolean enabled,
        
        /** Maximum records to fetch per poll. Default: 100. */
        int maxFetchRecords,
        
        /** Consumer group suffix for DLQ consumer. Default: "dlq-consumer". */
        String consumerGroupSuffix
) {
    public static DlqProperties defaults() {
        return new DlqProperties(false, 100, "dlq-consumer");
    }
}