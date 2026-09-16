package com.bhukkad.delivery.infrastructure.client;

import com.bhukkad.delivery.config.RoadDistanceProperties;
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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * HTTP client for OSRM road-router. Extracted from
 * {@link RoadDistanceService} so the Resilience4j proxy can intercept the
 * call — Spring AOP cannot intercept private self-invocation.
 *
 * <p>Emits Micrometer metrics for every call so the observability stack can
 * alert on OSRM queue-time, error rate, and latency under heavy traffic.</p>
 */
@Slf4j
@Component
public class OsrmClient {

    private final RoadDistanceProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public OsrmClient(RoadDistanceProperties properties,
                      @Qualifier("osrmRestTemplate") RestTemplate restTemplate,
                      ObjectMapper objectMapper,
                      MeterRegistry meterRegistry) {
        this.properties = properties;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @CircuitBreaker(name = "osrm", fallbackMethod = "osrmFallback")
    @Bulkhead(name = "osrm", fallbackMethod = "osrmFallback")
    @Retry(name = "osrm")
    @TimeLimiter(name = "osrm", fallbackMethod = "osrmTimeoutFallback")
    public Optional<RoadDistanceService.RoadRoute> fetchRoute(double fromLat, double fromLon,
                                                              double toLat, double toLon) {
        long start = System.nanoTime();
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            String url = properties.getOsrmUrl()
                    + "/route/v1/driving/" + fromLon + "," + fromLat + ";" + toLon + "," + toLat
                    + "?overview=false&steps=false";
            String body = restTemplate.getForObject(url, String.class);
            if (body == null) {
                sample.stop(meterRegistry.timer("osrm.request.duration", "result", "empty"));
                meterRegistry.counter("osrm.requests", "result", "empty").increment();
                return Optional.empty();
            }
            JsonNode root = objectMapper.readTree(body);
            JsonNode route = root.path("routes").path(0);
            if (route.isMissingNode()) {
                log.warn("OSRM route missing | from=({},{}) to=({},{})", fromLat, fromLon, toLat, toLon);
                sample.stop(meterRegistry.timer("osrm.request.duration", "result", "missing"));
                meterRegistry.counter("osrm.requests", "result", "missing").increment();
                return Optional.empty();
            }
            double distanceMeters = route.path("distance").asDouble(0);
            double durationSeconds = route.path("duration").asDouble(0);
            if (distanceMeters <= 0 || durationSeconds <= 0) {
                sample.stop(meterRegistry.timer("osrm.request.duration", "result", "invalid"));
                meterRegistry.counter("osrm.requests", "result", "invalid").increment();
                return Optional.empty();
            }
            RoadDistanceService.RoadRoute result =
                    new RoadDistanceService.RoadRoute(distanceMeters / 1000.0, durationSeconds / 60.0, true);
            log.debug("OSRM route | km={} | min={}", result.distanceKm(), result.durationMin());
            sample.stop(meterRegistry.timer("osrm.request.duration", "result", "ok"));
            meterRegistry.counter("osrm.requests", "result", "ok").increment();
            return Optional.of(result);
        } catch (Exception ex) {
            sample.stop(meterRegistry.timer("osrm.request.duration", "result", "error"));
            meterRegistry.counter("osrm.requests", "result", "error").increment();
            if (ex instanceof org.springframework.web.client.HttpClientErrorException) {
                meterRegistry.counter("osrm.errors", "type", "client").increment();
            } else if (ex instanceof org.springframework.web.client.HttpServerErrorException) {
                meterRegistry.counter("osrm.errors", "type", "server").increment();
            } else if (ex instanceof SocketTimeoutException) {
                meterRegistry.counter("osrm.errors", "type", "timeout").increment();
            } else if (ex instanceof IOException) {
                meterRegistry.counter("osrm.errors", "type", "io").increment();
            } else {
                meterRegistry.counter("osrm.errors", "type", "other").increment();
            }
            if (ex instanceof org.springframework.web.client.RestClientException) {
                throw new RuntimeException(ex);
            }
            if (ex instanceof com.fasterxml.jackson.core.JsonProcessingException) {
                log.warn("OSRM malformed payload | from=({},{}) to=({},{}) | error={}",
                        fromLat, fromLon, toLat, toLon, ex.getMessage());
                throw new RuntimeException(ex);
            }
            if (ex instanceof IOException) {
                log.warn("OSRM I/O error | from=({},{}) to=({},{}) | error={}",
                        fromLat, fromLon, toLat, toLon, ex.getMessage());
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
