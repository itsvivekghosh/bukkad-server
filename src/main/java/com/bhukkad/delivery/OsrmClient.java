package com.bhukkad.delivery;

import com.bhukkad.config.RoadDistanceProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

/**
 * HTTP client for OSRM road-router. Extracted from
 * {@link RoadDistanceService} so the Resilience4j proxy can intercept the
 * call — Spring AOP cannot intercept private self-invocation.
 */
@Slf4j
@Component
public class OsrmClient {

    private final RoadDistanceProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public OsrmClient(RoadDistanceProperties properties,
                      @Qualifier("osrmRestTemplate") RestTemplate restTemplate,
                      ObjectMapper objectMapper) {
        this.properties = properties;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @CircuitBreaker(name = "osrm", fallbackMethod = "osrmFallback")
    @Bulkhead(name = "osrm", fallbackMethod = "osrmFallback")
    @Retry(name = "osrm")
    @TimeLimiter(name = "osrm", fallbackMethod = "osrmTimeoutFallback")
    public Optional<RoadDistanceService.RoadRoute> fetchRoute(double fromLat, double fromLon,
                                                              double toLat, double toLon) {
        try {
            String url = properties.getOsrmUrl()
                    + "/route/v1/driving/" + fromLon + "," + fromLat + ";" + toLon + "," + toLat
                    + "?overview=false&steps=false";
            String body = restTemplate.getForObject(url, String.class);
            if (body == null) {
                return Optional.empty();
            }
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
            RoadDistanceService.RoadRoute result =
                    new RoadDistanceService.RoadRoute(distanceMeters / 1000.0, durationSeconds / 60.0, true);
            log.debug("OSRM route | km={} | min={}", result.distanceKm(), result.durationMin());
            return Optional.of(result);
        } catch (Exception ex) {
            if (ex instanceof org.springframework.web.client.RestClientException) {
                throw new RuntimeException(ex);
            }
            if (ex instanceof com.fasterxml.jackson.core.JsonProcessingException) {
                log.warn("OSRM malformed payload | from=({},{}) to=({},{}) | error={}",
                        fromLat, fromLon, toLat, toLon, ex.getMessage());
                throw new RuntimeException(ex);
            }
            if (ex instanceof java.io.IOException) {
                throw new RuntimeException(ex);
            }
            log.warn("OSRM route error | from=({},{}) to=({},{}) | error={}",
                    fromLat, fromLon, toLat, toLon, ex.getMessage());
            throw new RuntimeException(ex);
        }
    }

    @SuppressWarnings("unused")
    public Optional<RoadDistanceService.RoadRoute> osrmFallback(double fromLat, double fromLon,
                                                                double toLat, double toLon,
                                                                Throwable ex) {
        log.warn("OSRM unavailable, using haversine fallback | error={}", ex.getMessage());
        return Optional.empty();
    }

    @SuppressWarnings("unused")
    public Optional<RoadDistanceService.RoadRoute> osrmTimeoutFallback(double fromLat, double fromLon,
                                                                       double toLat, double toLon,
                                                                       Throwable ex) {
        log.warn("OSRM timeout | from=({},{}) to=({},{}) | error={}", fromLat, fromLon, toLat, toLon, ex.getMessage());
        return Optional.empty();
    }
}
