package com.bhukkad.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for smarter ETA and delivery-truth features (V14).
 */
@Data
@ConfigurationProperties(prefix = "app.delivery-truth")
public class DeliveryTruthProperties {
    /** Base rider speed in km per minute (~36 km/h). */
    private double avgSpeedKmPerMin = 0.6;
    /** Buffer minutes added when picking up from restaurant. */
    private int pickupBufferMinutes = 8;
    /** ETA confidence band width in minutes. */
    private int confidenceBandMinutes = 5;
    /** Whether to persist ETA snapshots on each computation. */
    private boolean recordSnapshots = true;
}
