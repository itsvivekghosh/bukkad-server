package com.bhukkad.delivery;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Rider location ping retention (perf audit §2.3/§4.3):
 * {@code rider_location_updates} was append-only and grew unbounded. The
 * purge deletes {@code recorded_at} older than {@code cutoffHours} in
 * {@code batchSize} row chunks (at most {@code maxBatches} chunks per tick,
 * so a backlog drains incrementally without long lock holds).
 */
@Data
@ConfigurationProperties(prefix = "app.rider-location-retention")
public class RiderLocationRetentionProperties {

    /** Retention window; rows newer than this stay. */
    private int cutoffHours = 72;
    /** Rows deleted per statement; the scheduler loops until drained or the cap is hit. */
    private int batchSize = 5000;
    /** Upper bound on delete statements per tick. */
    private int maxBatches = 50;
}
