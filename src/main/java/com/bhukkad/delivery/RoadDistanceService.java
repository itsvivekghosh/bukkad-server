package com.bhukkad.delivery;

import com.bhukkad.config.RoadDistanceProperties;
import com.bhukkad.util.DistanceCalculator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Resolves real road distance / travel time between two coordinates.
 *
 * <p>When {@code app.road-distance.enabled=true} and an OSRM server is
 * configured, distance and duration come from the OSRM route API (the single
 * biggest delivery-quality win — haversine straight-line distance understates
 * real trips in dense areas). If OSRM is unavailable or the call fails, the
 * service falls back to the haversine approximation so dispatch and ETA keep
 * working (graceful degradation).</p>
 *
 * <p>All external calls sit behind a circuit breaker so a failing OSRM server
 * never stalls rider dispatch.</p>
 */
@Slf4j
@Service
@EnableConfigurationProperties(RoadDistanceProperties.class)
public class RoadDistanceService {

    private final RoadDistanceProperties properties;
    private final OsrmClient osrmClient;

    public RoadDistanceService(RoadDistanceProperties properties,
                               OsrmClient osrmClient) {
        this.properties = properties;
        this.osrmClient = osrmClient;
    }

    /**
     * A road route between two points.
     *
     * @param distanceKm   distance along the road network in kilometres
     * @param durationMin  estimated travel time in minutes
     * @param fromOsrm     true when the value came from the OSRM API, false for haversine fallback
     */
    public record RoadRoute(double distanceKm, double durationMin, boolean fromOsrm) {
    }

    /**
     * Returns the road route between the given coordinates, falling back to the
     * haversine approximation when OSRM is disabled, unreachable, or errors.
     */
    public RoadRoute route(double fromLat, double fromLon, double toLat, double toLon) {
        if (properties.isEnabled() && !properties.getOsrmUrl().isBlank()) {
            try {
                Optional<RoadRoute> osrm = osrmClient.fetchRoute(fromLat, fromLon, toLat, toLon);
                if (osrm.isPresent()) {
                    return osrm.get();
                }
            } catch (Exception ex) {
                log.warn("OSRM error, using haversine fallback | error={}", ex.getMessage());
            }
        }
        double km = DistanceCalculator.calculateDistance(fromLat, fromLon, toLat, toLon);
        double minutes = km / properties.getFallbackSpeedKmPerMin();
        return new RoadRoute(km, minutes, false);
    }

    /** True when OSRM road routing is currently active (not the haversine fallback). */
    public boolean isRoadRoutingActive() {
        return properties.isEnabled() && !properties.getOsrmUrl().isBlank();
    }
}
