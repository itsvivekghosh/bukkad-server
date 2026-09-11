package com.bhukkad.delivery.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Rider dispatch matching (PERF-4 / ADR-003).
 *
 * <p>{@code app.delivery.geo-matching.enabled} is the ADR-003 feature flag:
 * default off in dev, to be turned on in a prod overlay only after the flag
 * has soaked. The active-load cap is NOT flagged — it is the same class of
 * unconditional atomicity guard as {@code uq_delivery_assignments_order}
 * (migration V9).</p>
 */
@Data
@ConfigurationProperties(prefix = "app.delivery")
public class DeliveryMatchingProperties {

    /** ADR-003 proximity matcher over rider last-known positions. */
    private final GeoMatching geoMatching = new GeoMatching();

    /** Max simultaneously-active assignments per rider (conditional UPDATE cap). */
    private int activeLoadCap = 4;

    /** A rider's last-known position older than this is not dispatch evidence (minutes). */
    private int positionFreshnessMinutes = 15;

    /** Upper bound on positioned candidates examined per assign. */
    private int candidateLimit = 20;

    @Data
    public static class GeoMatching {
        /** {@code app.delivery.geo-matching.enabled} (ADR-003 naming). */
        private boolean enabled = false;
    }
}
