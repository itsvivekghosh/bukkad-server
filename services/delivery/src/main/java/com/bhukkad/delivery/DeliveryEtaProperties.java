package com.bhukkad.delivery;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the live-ETA port (P3 / ADR-003).
 *
 * <p>While {@code enabled=false} (the shipped default) {@code DefaultEtaPort}
 * keeps returning {@code Optional.empty()} — the pre-P3 behavior, byte-identical
 * payloads for SSE status events. When enabled, the port produces real
 * {@code EtaSnapshot}s: geo-exact via {@code EtaService} whenever a fresh rider
 * position AND the order drop-off coordinates are both known, otherwise the
 * monolith's status+traffic heuristic (delivery's local schema carries no
 * order destination, so the heuristic stays as an honest degradation).</p>
 */
@Data
@ConfigurationProperties(prefix = "app.delivery.eta")
public class DeliveryEtaProperties {

    /** Master gate for registering EtaService + serving live ETA snapshots. */
    private boolean enabled = false;

    /** Heuristic minutes while the rider still works the pickup leg. */
    private int fallbackMinutesToPickup = 15;

    /** Heuristic minutes once the order is on its way to the customer. */
    private int fallbackMinutesToCustomer = 12;

    /** ETA confidence band, mirroring the monolith OrderEtaService band. */
    private int confidenceBandMinutes = 5;

    /** A rider position older than this is not usable for geo-exact ETA. */
    private int locationFreshnessMinutes = 10;
}
