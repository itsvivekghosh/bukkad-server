package com.bhukkad.delivery;

import com.bhukkad.config.RoadDistanceProperties;
import com.bhukkad.util.DistanceCalculator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

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
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public RoadDistanceService(RoadDistanceProperties properties,
                               RestTemplate restTemplate,
                               ObjectMapper objectMapper) {
        this.properties = properties;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
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
            Optional<RoadRoute> osrm = tryOsrm(fromLat, fromLon, toLat, toLon);
            if (osrm.isPresent()) {
                return osrm.get();
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

    @CircuitBreaker(name = "osrm", fallbackMethod = "osrmFallback")
    private Optional<RoadRoute> tryOsrm(double fromLat, double fromLon, double toLat, double toLon) {
        try {
            String url = properties.getOsrmUrl()
                    + "/route/v1/driving/" + fromLon + "," + fromLat + ";" + toLon + "," + toLat
                    + "?overview=false&steps=false";
            String body = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(body);
            JsonNode route = root.path("routes").path(0);
            if (route.isMissingNode()) {
                log.warn("OSRM route missing | from=({},{}) to=({},{})", fromLat, fromLon, toLat, toLon);
                return Optional.empty();
            }
            double distanceMeters = route.path("distance").asDouble(0);
            double durationSeconds = route.path("duration").asDouble(0);
            if (distanceMeters <= 0 || durationSeconds <= 0) {
                return Optional.empty();
            }
            RoadRoute result = new RoadRoute(distanceMeters / 1000.0, durationSeconds / 60.0, true);
            log.debug("OSRM route | km={} | min={}", result.distanceKm(), result.durationMin());
            return Optional.of(result);
        } catch (Exception ex) {
            // Malformed payload or transport error → caller falls back to haversine.
            log.warn("OSRM route error | from=({},{}) to=({},{}) | error={}",
                    fromLat, fromLon, toLat, toLon, ex.getMessage());
            return Optional.empty();
        }
    }

    @SuppressWarnings("unused")
    private Optional<RoadRoute> osrmFallback(double fromLat, double fromLon, double toLat, double toLon,
                                             Throwable ex) {
        log.warn("OSRM unavailable, using haversine fallback | error={}", ex.getMessage());
        return Optional.empty();
    }
}
