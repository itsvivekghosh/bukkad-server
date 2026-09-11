package com.bhukkad.delivery;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ADR-003 nearest-rider assignment matching (P3).
 *
 * <p>Default OFF → {@code DeliveryService.assign} keeps the legacy
 * {@code findFirstByIsActiveTrue} pick (zero behavior change). When enabled,
 * the matcher selects the closest ACTIVE rider to the anchor point using the
 * freshest row of {@code rider_location_updates} per agent (the ADR-mandated
 * index path — no PostGIS, no new columns), and refuses riders already at
 * their active-assignment cap.</p>
 */
@Data
@ConfigurationProperties(prefix = "app.delivery.geo-matching")
public class GeoMatchingProperties {

    /** Master switch for proximity selection in assign(). */
    private boolean enabled = false;

    /** A rider position older than this is not "last known" — excluded. */
    private int locationFreshnessMinutes = 10;

    /** Active (not-yet-delivered) assignments allowed per rider at pick time. */
    private int maxActiveAssignments = 3;

    /** Hard bound on the candidate set loaded per match attempt (memory guard). */
    private int candidateLimit = 50;
}
