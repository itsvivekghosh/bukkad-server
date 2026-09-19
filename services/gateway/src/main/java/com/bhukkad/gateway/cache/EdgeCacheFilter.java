package com.bhukkad.gateway.cache;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import org.reactivestreams.Publisher;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Random;
import java.util.Set;

/**
 * Edge response cache for idempotent public GET endpoints.
 *
 * <p>Caches successful (200) JSON responses in Redis with a per-route TTL.
 * Cache keys include the full normalized path + sorted query params so
 * `/api/v1/restaurants/public?city=Bangalore` and
 * `/api/v1/restaurants/public?city=Delhi` are distinct entries.</p>
 *
 * <p>This filter is deliberately placed after rate limiting (order -30) but
 * before routing so a cache hit short-circuits the upstream call entirely.</p>
 */
@Component
public class EdgeCacheFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(EdgeCacheFilter.class);
    static final int ORDER = -20; // after rate limit (-30), before kill switch (10)
    private static final String KEY_PREFIX = "edge:cache:";
    private static final String CONTENT_TYPE_JSON = "application/json";
    public static final Set<String> PUBLIC_GET_PREFIXES = Set.of(
            "/api/v1/restaurants/public",
            "/api/v1/cuisines",
            "/api/v1/menu/items",
            "/api/v1/reviews",
            "/api/v1/feed",
            "/api/v1/search",
            "/api/v1/home/feed",
            "/api/v1/home/banners",
            "/api/v1/home/trending"
    );

    private static final Random JITTER_RANDOM = new Random();
    private static final double JITTER_FACTOR = 0.1;

    private final ObjectProvider<ReactiveStringRedisTemplate> redisProvider;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;
    private final boolean enabled;
    private final long ttlSeconds;

    public EdgeCacheFilter(ObjectProvider<ReactiveStringRedisTemplate> redisProvider,
                           ObjectProvider<MeterRegistry> meterRegistryProvider,
                           @org.springframework.beans.factory.annotation.Value("${app.edge.cache.enabled:true}") boolean enabled,
                           @org.springframework.beans.factory.annotation.Value("${app.edge.cache.ttl-seconds:60}") long ttlSeconds) {
        this.redisProvider = redisProvider;
        this.meterRegistryProvider = meterRegistryProvider;
        this.enabled = enabled;
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!enabled) {
            return chain.filter(exchange);
        }

        ServerHttpRequest request = exchange.getRequest();
        if (!HttpMethod.GET.equals(request.getMethod())) {
            return chain.filter(exchange);
        }

        String path = request.getURI().getPath();
        boolean cacheable = PUBLIC_GET_PREFIXES.stream().anyMatch(path::startsWith);
        if (!cacheable) {
            return chain.filter(exchange);
        }

        ReactiveStringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return chain.filter(exchange);
        }

        String cacheKey = buildCacheKey(request);
        Mono<String> cacheRead = redis.opsForValue().get(cacheKey)
                .doOnError(e -> log.warn("EDGE_CACHE_READ_FAILED key={} error={}", cacheKey, e.getMessage()))
                .onErrorResume(e -> {
                    log.warn("EDGE_CACHE_UNAVAILABLE key={} falling through to upstream", cacheKey);
                    return Mono.empty();
                });

        return cacheRead.flatMap(cached -> {
                    ServerHttpResponse response = exchange.getResponse();
                    response.getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
                    response.getHeaders().set("X-Edge-Cache", "HIT");
                    MeterRegistry meters = meterRegistryProvider.getIfAvailable();
                    if (meters != null) {
                        meters.counter("edge_cache_hit").increment();
                    }
                    response.setStatusCode(HttpStatus.OK);
                    return response.writeWith(Mono.just(response.bufferFactory().wrap(cached.getBytes(StandardCharsets.UTF_8))))
                            .then(Mono.just(new Object()));
                })
                .switchIfEmpty(Mono.defer(() -> {
                    MeterRegistry meters = meterRegistryProvider.getIfAvailable();
                    if (meters != null) {
                        meters.counter("edge_cache_miss").increment();
                    }
                    if (redis != null && ttlSeconds > 0) {
                        ServerHttpResponse originalResponse = exchange.getResponse();
                        ServerHttpResponseDecorator decorator = new ServerHttpResponseDecorator(originalResponse) {
                            @Override
                            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                                return DataBufferUtils.join(body)
                                        .flatMap(dataBuffer -> {
                                            byte[] bytes = new byte[dataBuffer.readableByteCount()];
                                            dataBuffer.read(bytes);
                                            DataBufferUtils.release(dataBuffer);
                                            String bodyStr = new String(bytes, StandardCharsets.UTF_8);
                                            long jitteredTtl = ttlSeconds + (long) ((JITTER_RANDOM.nextDouble() - 0.5) * 2 * ttlSeconds * JITTER_FACTOR);
                                            redis.opsForValue().set(cacheKey, bodyStr, Duration.ofSeconds(jitteredTtl))
                                                    .doOnError(e -> log.warn("EDGE_CACHE_WRITE_FAILED key={} error={}", cacheKey, e.getMessage()))
                                                    .onErrorResume(e -> Mono.empty())
                                                    .subscribe();
                                            return originalResponse.writeWith(Mono.just(originalResponse.bufferFactory().wrap(bytes)));
                                        });
                            }
                        };
                        return chain.filter(exchange.mutate().response(decorator).build())
                                .doOnError(e -> log.warn("EDGE_CACHE_WRITE_FAILED key={} error={}", cacheKey, e.getMessage()))
                                .then(Mono.just(new Object()));
                    }
                    return chain.filter(exchange)
                            .doOnError(e -> log.warn("EDGE_CACHE_WRITE_FAILED key={} error={}", cacheKey, e.getMessage()))
                            .then(Mono.just(new Object()));
                }))
                .then();
    }

    public static String buildCacheKey(ServerHttpRequest request) {
        StringBuilder sb = new StringBuilder(KEY_PREFIX);
        sb.append(request.getMethod()).append(":")
          .append(request.getURI().getPath());
        if (request.getQueryParams() != null && !request.getQueryParams().isEmpty()) {
            request.getQueryParams().entrySet().stream()
                    .sorted(java.util.Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        sb.append('?').append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
                        for (String v : entry.getValue()) {
                            sb.append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8));
                        }
                    });
        }
        return sb.toString();
    }

    public static boolean isCacheableGet(ServerHttpRequest request) {
        return HttpMethod.GET.equals(request.getMethod())
                && PUBLIC_GET_PREFIXES.stream().anyMatch(
                        p -> request.getURI().getPath().startsWith(p));
    }
}
