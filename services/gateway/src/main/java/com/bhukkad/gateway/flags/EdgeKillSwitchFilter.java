package com.bhukkad.gateway.flags;

import com.bhukkad.common.security.PlatformJwtValidator;
import com.bhukkad.gateway.cache.EdgeCacheFilter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Per-route edge kill switch with Redis-backed fallback (gap A10 resilience).
 *
 * <p>When a flag resolves disabled the request is short-circuited with a 503.
 * For cacheable public GET endpoints the filter first attempts to serve a
 * cached response from Redis (populated by {@link EdgeCacheFilter}); on a miss
 * it falls back to an empty JSON array {@code []} with {@code Retry-After}.
 * Non-GET requests always receive the maintenance envelope. Redis errors
 * degrade to the envelope so the kill switch never depends on a successful
 * Redis round-trip.</p>
 *
 * <p>Percentage rollouts bucket on the caller's id taken from the bearer JWT
 * when the gateway shares the identity signing secret (anonymous callers follow
 * the boolean/global state).</p>
 */
@Component
public class EdgeKillSwitchFilter implements org.springframework.cloud.gateway.filter.GlobalFilter, Ordered {

    public static final String ROUTE_FLAG_METADATA = "edge-flag";
    static final int ORDER = 10;

    private static final Logger log = LoggerFactory.getLogger(EdgeKillSwitchFilter.class);

    private static final Duration FALLBACK_CACHE_TIMEOUT = Duration.ofMillis(300);
    private static final String METRIC_FALLBACK_SERVED = "edge_kill_switch_fallback_served";

    private final EdgeFeatureFlags flags;
    private final ObjectProvider<PlatformJwtValidator> jwtValidatorProvider;
    private final ObjectProvider<ReactiveStringRedisTemplate> redisProvider;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;
    private final int maintenanceRetryAfterSeconds;

    public EdgeKillSwitchFilter(EdgeFeatureFlags flags,
                                ObjectProvider<PlatformJwtValidator> jwtValidatorProvider,
                                ObjectProvider<ReactiveStringRedisTemplate> redisProvider,
                                ObjectProvider<MeterRegistry> meterRegistryProvider,
                                @org.springframework.beans.factory.annotation.Value(
                                        "${app.edge.kill-switch.maintenance-retry-after:30}") int maintenanceRetryAfterSeconds) {
        this.flags = flags;
        this.jwtValidatorProvider = jwtValidatorProvider;
        this.redisProvider = redisProvider;
        this.meterRegistryProvider = meterRegistryProvider;
        this.maintenanceRetryAfterSeconds = maintenanceRetryAfterSeconds;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, org.springframework.cloud.gateway.filter.GatewayFilterChain chain) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        Object flagMeta = route == null ? null : route.getMetadata().get(ROUTE_FLAG_METADATA);
        if (!(flagMeta instanceof String flagKey)) {
            return chain.filter(exchange);
        }
        return Mono.fromCallable(() -> subjectId(exchange))
                .subscribeOn(Schedulers.boundedElastic())
                .defaultIfEmpty(0L)
                .flatMap(subject -> flags.isRouteEnabled(flagKey, subject == 0L ? null : subject))
                .flatMap(isEnabled -> isEnabled
                        ? chain.filter(exchange)
                        : disabled(exchange, flagKey, route.getId()));
    }

    private Mono<Void> disabled(ServerWebExchange exchange, String flagKey, String routeId) {
        log.info("EDGE_KILL_SWITCH_BLOCKED | route={} | flag={} | path={}",
                routeId, flagKey, exchange.getRequest().getPath());

        ServerHttpRequest request = exchange.getRequest();
        if (EdgeCacheFilter.isCacheableGet(request)) {
            ReactiveStringRedisTemplate redis = redisProvider.getIfAvailable();
            if (redis != null) {
                return serveCachedFallback(exchange, routeId, request, redis);
            }
        }
        return maintenanceEnvelope(exchange, routeId, flagKey);
    }

    private Mono<Void> serveCachedFallback(ServerWebExchange exchange, String routeId,
                                           ServerHttpRequest request,
                                           ReactiveStringRedisTemplate redis) {
        String cacheKey = EdgeCacheFilter.buildCacheKey(request);
        return redis.opsForValue().get(cacheKey)
                .timeout(FALLBACK_CACHE_TIMEOUT)
                .doOnError(e -> log.warn("EDGE_KILL_SWITCH_CACHE_READ_FAILED key={} error={}", cacheKey, e.getMessage()))
                .onErrorResume(e -> Mono.empty())
                .flatMap(cached -> {
                    countMetric(METRIC_FALLBACK_SERVED, "source", "cache-hit");
                    ServerHttpResponse response = exchange.getResponse();
                    response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    response.getHeaders().add("Retry-After", String.valueOf(maintenanceRetryAfterSeconds));
                    byte[] body = cached.getBytes(StandardCharsets.UTF_8);
                    return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
                })
                .switchIfEmpty(Mono.defer(() -> {
                    countMetric(METRIC_FALLBACK_SERVED, "source", "cache-miss");
                    ServerHttpResponse response = exchange.getResponse();
                    response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    response.getHeaders().add("Retry-After", String.valueOf(maintenanceRetryAfterSeconds));
                    byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
                    return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
                }))
                .then();
    }

    private Mono<Void> maintenanceEnvelope(ServerWebExchange exchange, String routeId, String flagKey) {
        countMetric(METRIC_FALLBACK_SERVED, "source", "maintenance");
        exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().add("Retry-After", String.valueOf(maintenanceRetryAfterSeconds));
        String path = exchange.getRequest().getURI().getPath();
        String body = "{"
                + "\"status\":503,"
                + "\"code\":\"SERVICE_DISABLED\","
                + "\"message\":\"route disabled by feature flag " + flagKey + "\","
                + "\"path\":\"" + path + "\","
                + "\"timestamp\":\"" + java.time.Instant.now().toString() + "\""
                + "}";
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    private void countMetric(String name, String key, String value) {
        MeterRegistry meters = meterRegistryProvider.getIfAvailable();
        if (meters != null) {
            meters.counter(name, key, value).increment();
        }
    }

    /** Best-effort caller id for percentage rollouts (never a security decision). */
    private Long subjectId(ServerWebExchange exchange) {
        String header = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        PlatformJwtValidator validator = jwtValidatorProvider.getIfAvailable();
        if (validator == null) {
            return null;
        }
        try {
            return validator.validate(header.substring(7))
                    .map(principal -> principal.userId())
                    .orElse(null);
        } catch (Exception ex) {
            return null;
        }
    }
}
