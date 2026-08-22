package com.bhukkad.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for road-network distance/ETA (P1-2).
 *
 * <p>By default no OSRM endpoint is configured and the app falls back to the
 * haversine straight-line distance — exactly the legacy behaviour. When an
 * {@code osrmUrl} is provided (e.g. a self-hosted OSRM instance), the
 * {@link com.bhukkad.delivery.RoadDistanceService} uses real road distance and
 * travel time for ETA and rider dispatch.</p>
 */
@Data
@ConfigurationProperties(prefix = "app.road-distance")
public class RoadDistanceProperties {

    /** Whether road-distance lookup is enabled at all. */
    private boolean enabled = false;

    /** Base OSRM server URL, e.g. https://router.project-osrm.org (no trailing slash). */
    private String osrmUrl = "";

    /** Connect timeout for the OSRM call in milliseconds. */
    private long connectTimeoutMs = 2000;

    /** Read timeout for the OSRM call in milliseconds. */
    private long readTimeoutMs = 2000;

    /** Average speed in km/min used to convert distance to minutes when OSRM is unavailable. */
    private double fallbackSpeedKmPerMin = 0.6;
}
