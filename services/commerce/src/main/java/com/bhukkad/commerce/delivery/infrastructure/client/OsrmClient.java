package com.bhukkad.commerce.delivery.infrastructure.client;

import com.bhukkad.commerce.delivery.config.RoadDistanceProperties;
import com.bhukkad.common.web.client.PlatformWebClientBuilderFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;

/**
 * HTTP client for OSRM road-router using WebClient (non-blocking), built via
 * {@link PlatformWebClientBuilderFactory} for the shared bounded connection
 * pool, per-target circuit breaker, and retry filter.
 *
 * <p>Emits Micrometer metrics for every call so the observability stack can
 * alert on OSRM queue-time, error rate, and latency under heavy traffic.</p>
 *
 * <p>Reactive contract: {@link #fetchRoute} returns {@link Mono<RoadDistanceService.RoadRoute>}
 * — empty when OSRM returns no usable route (callers fall back to haversine),
 * error signal for hard failures (timeouts, transport errors) so the circuit
 * breaker can trip. Callers block only at their own edge with a short timeout.</p>
 */
@Slf4j
@Component
public class OsrmClient {

    private static final String TARGET = "osrm";

    private final RoadDistanceProperties properties;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public OsrmClient(RoadDistanceProperties properties,
                      ObjectMapper objectMapper,
                      MeterRegistry meterRegistry) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        String baseUrl = properties.getOsrmUrl();
        this.webClient = PlatformWebClientBuilderFactory.forTarget(TARGET, meterRegistry)
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .responseTimeout(Duration.ofMillis(properties.getReadTimeoutMs()))
                .build()
                .mutate()
                .baseUrl(baseUrl)
                .build();
    }

    /**
     * Fetches a road route from OSRM.
     *
     * @return {@link Mono#empty()} when OSRM returns no usable route, an error
     *         signal for hard failures (transient failures are retried per the
     *         factory's RetryFilter; hard errors propagate so callers can
     *         fall back to haversine).
     */
    public Mono<RoadDistanceService.RoadRoute> fetchRoute(double fromLat, double fromLon,
                                                          double toLat, double toLon) {
        long start = System.nanoTime();
        Timer.Sample sample = Timer.start(meterRegistry);
        String url = "/route/v1/driving/" + fromLon + "," + fromLat + ";" + toLon + "," + toLat
                + "?overview=false&steps=false";

        return webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofMillis(properties.getReadTimeoutMs()))
                // Retry with jitter on top of the factory's RetryFilter:
                // the OSRM GET is idempotent, so retrying is safe. Jitter
                // spreads retries to avoid thundering-herd on recovery.
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
                        .jitter(0.2)
                        .filter(t -> isTransient(t)))
                .flatMap(body -> parseRoute(body, start, fromLat, fromLon, toLat, toLon, sample))
                .onErrorResume(e -> recordError(e, start, fromLat, fromLon, toLat, toLon, sample));
    }

    private Mono<RoadDistanceService.RoadRoute> parseRoute(String body, long start,
                                                           double fromLat, double fromLon,
                                                           double toLat, double toLon,
                                                           Timer.Sample sample) {
        if (body == null) {
            recordResult("empty", sample);
            return Mono.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode route = root.path("routes").path(0);
            if (route.isMissingNode()) {
                log.warn("OSRM route missing | from=({},{}) to=({},{})", fromLat, fromLon, toLat, toLon);
                recordResult("missing", sample);
                return Mono.empty();
            }
            double distanceMeters = route.path("distance").asDouble(0);
            double durationSeconds = route.path("duration").asDouble(0);
            if (distanceMeters <= 0 || durationSeconds <= 0) {
                recordResult("invalid", sample);
                return Mono.empty();
            }
            RoadDistanceService.RoadRoute result =
                    new RoadDistanceService.RoadRoute(distanceMeters / 1000.0, durationSeconds / 60.0, true);
            log.debug("OSRM route | km={} | min={}", result.distanceKm(), result.durationMin());
            recordResult("ok", sample);
            return Mono.just(result);
        } catch (Exception ex) {
            return recordParseError(ex, start, fromLat, fromLon, toLat, toLon, sample);
        }
    }

    private Mono<RoadDistanceService.RoadRoute> recordError(Throwable ex, long start,
                                                            double fromLat, double fromLon,
                                                            double toLat, double toLon,
                                                            Timer.Sample sample) {
        sample.stop(meterRegistry.timer("osrm.request.duration", "result", "error"));
        meterRegistry.counter("osrm.requests", "result", "error").increment();
        if (ex instanceof java.net.SocketTimeoutException
                || ex instanceof java.util.concurrent.TimeoutException
                || ex instanceof io.netty.handler.timeout.TimeoutException) {
            meterRegistry.counter("osrm.errors", "type", "timeout").increment();
        } else if (ex instanceof org.springframework.web.reactive.function.client.WebClientResponseException) {
            meterRegistry.counter("osrm.errors", "type", "server").increment();
        } else {
            meterRegistry.counter("osrm.errors", "type", "other").increment();
        }
        log.warn("OSRM route error | from=({},{}) to=({},{}) | error={}",
                fromLat, fromLon, toLat, toLon, ex.getMessage());
        return Mono.error(ex);
    }

    private Mono<RoadDistanceService.RoadRoute> recordParseError(Exception ex, long start,
                                                                 double fromLat, double fromLon,
                                                                 double toLat, double toLon,
                                                                 Timer.Sample sample) {
        sample.stop(meterRegistry.timer("osrm.request.duration", "result", "error"));
        meterRegistry.counter("osrm.requests", "result", "error").increment();
        meterRegistry.counter("osrm.errors", "type", "io").increment();
        if (ex instanceof com.fasterxml.jackson.core.JsonProcessingException) {
            log.warn("OSRM malformed payload | from=({},{}) to=({},{}) | error={}",
                    fromLat, fromLon, toLat, toLon, ex.getMessage());
        } else {
            log.warn("OSRM parse error | from=({},{}) to=({},{}) | error={}",
                    fromLat, fromLon, toLat, toLon, ex.getMessage());
        }
        return Mono.error(ex);
    }

    private void recordResult(String result, Timer.Sample sample) {
        sample.stop(meterRegistry.timer("osrm.request.duration", "result", result));
        meterRegistry.counter("osrm.requests", "result", result).increment();
    }

    private static boolean isTransient(Throwable t) {
        for (Throwable curr = t; curr != null && curr != curr.getCause(); curr = curr.getCause()) {
            if (curr instanceof java.net.SocketTimeoutException
                    || curr instanceof java.util.concurrent.TimeoutException
                    || curr instanceof java.net.ConnectException
                    || curr instanceof java.net.UnknownHostException
                    || curr instanceof io.netty.handler.timeout.TimeoutException) {
                return true;
            }
            if (curr instanceof org.springframework.web.reactive.function.client.WebClientResponseException) {
                int status = ((org.springframework.web.reactive.function.client.WebClientResponseException) curr)
                        .getStatusCode().value();
                return status == 408 || status >= 500;
            }
        }
        return false;
    }

    @SuppressWarnings("unused")
    public Mono<RoadDistanceService.RoadRoute> osrmFallback(double fromLat, double fromLon,
                                                            double toLat, double toLon,
                                                            Throwable ex) {
        log.warn("OSRM unavailable, using haversine fallback | error={}", ex.getMessage());
        return Mono.empty();
    }

    @SuppressWarnings("unused")
    public Mono<RoadDistanceService.RoadRoute> osrmTimeoutFallback(double fromLat, double fromLon,
                                                                   double toLat, double toLon,
                                                                   Throwable ex) {
        log.warn("OSRM timeout | from=({},{}) to=({},{}) | error={}", fromLat, fromLon, toLat, toLon, ex.getMessage());
        return Mono.empty();
    }
}
