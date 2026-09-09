package com.bhukkad.admin.compliance;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the old-order archival job (V55 {@code orders_archive}).
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.archival.orders")
public class OrderArchiveProperties {

    /** Orders older than this many days are moved to orders_archive. */
    private int retentionDays = 365;

    /** Rows moved per pass; keep small so each pass commits a bounded slice. */
    private int batchSize = 500;

    /** Scheduler interval in ms (default 1 hour). */
    private long intervalMs = 3_600_000;
}
