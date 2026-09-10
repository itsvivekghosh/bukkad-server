package com.bhukkad.gateway.flags;

import com.bhukkad.common.featureflag.FeatureFlagHash;
import com.bhukkad.common.featureflag.FeatureFlagProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Edge feature-flag reader for the gateway kill switch (migration plan W0 item /
 * gap A10). Mirrors admin-analytics' runtime flag state: every flag override
 * lives in the shared Redis hash {@code bhukkad:feature-flag:overrides} and
 * toggles are published on {@code bhukkad:feature-flag:changed}.
 *
 * <p>The gateway caches the whole hash and reloads it at most once per
 * {@link #CACHE_TTL} (30 s), additionally stamped stale on the pub/sub message,
 * so the edge decision never blocks on Redis per request (adds ≈0 ms while
 * Redis is warm, ≤1 s timeout while refreshing, and it is fail-open: Redis
 * outages never take traffic down — only an explicit {@code false} override
 * disables a route).</p>
 *
 * <p>Boolean state = Redis override → gateway-configured default
 * ({@code app.feature-flags.flags}) → enabled. Percentage rollouts
 * ({@code app.feature-flags.rollout}) bucket the caller by the shared
 * {@link FeatureFlagHash} algorithm, keeping edge and service decisions
 * consistent for the same user.</p>
 */
@Component
@EnableConfigurationProperties(FeatureFlagProperties.class)
public class EdgeFeatureFlags {

    static final String OVERRIDES_HASH = "bhukkad:feature-flag:overrides";
    static final String CHANGED_CHANNEL = "bhukkad:feature-flag:changed";
    static final Duration CACHE_TTL = Duration.ofSeconds(30);

    private static final Logger log = LoggerFactory.getLogger(EdgeFeatureFlags.class);

    private final ObjectProvider<ReactiveStringRedisTemplate> redisProvider;
    /** Dedicated relay provider (G-2 isolation: subscribe must NOT hit the shared pool). */
    private final ObjectProvider<ReactiveStringRedisTemplate> relayProvider;
    private final FeatureFlagProperties properties;

    private final Map<String, String> overrides = new ConcurrentHashMap<>();
    private final AtomicLong lastLoadNanos = new AtomicLong(0);
    private Disposable subscription;

    public EdgeFeatureFlags(
            @org.springframework.beans.factory.annotation.Qualifier("reactiveStringRedisTemplate") ObjectProvider<ReactiveStringRedisTemplate> redisProvider,
            @org.springframework.beans.factory.annotation.Qualifier("sseRelayReactiveStringRedisTemplate") ObjectProvider<ReactiveStringRedisTemplate> relayProvider,
            FeatureFlagProperties properties) {
        this.redisProvider = redisProvider;
        this.relayProvider = relayProvider;
        this.properties = properties;
    }

    @PostConstruct
    void subscribeInvalidations() {
        ReactiveStringRedisTemplate redis = relayProvider.getIfAvailable();
        if (redis == null) {
            log.info("EDGE_FLAGS_REDIS_ABSENT | kill-switch operates on configured defaults only");
            return;
        }
        try {
            subscription = redis.listenToChannel(CHANGED_CHANNEL)
                    .doOnNext(message -> lastLoadNanos.set(0))
                    .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(5)).maxBackoff(Duration.ofMinutes(1)))
                    .subscribe();
        } catch (Exception ex) {
            log.warn("EDGE_FLAG_SUBSCRIBE_FAILED | error={}", ex.getMessage());
        }
    }

    @PreDestroy
    void closeSubscription() {
        if (subscription != null) {
            subscription.dispose();
        }
    }

    /** Reactive kill-switch decision; refreshes the snapshot asynchronously when stale. */
    public Mono<Boolean> isRouteEnabled(String flagKey, Long subjectId) {
        return refreshWhenStale()
                .thenReturn(evaluateNow(flagKey, subjectId))
                .defaultIfEmpty(evaluateNow(flagKey, subjectId));
    }

    /** Immediate decision from the current snapshot (no Redis access). */
    boolean evaluateNow(String flagKey, Long subjectId) {
        return FeatureFlagHash.inRollout(flagKey, subjectId,
                properties.getRolloutPercent(flagKey), effectiveBoolean(flagKey));
    }

    private Boolean effectiveBoolean(String flagKey) {
        String override = overrides.get(flagKey);
        if (override != null) {
            return Boolean.parseBoolean(override);
        }
        if (properties.getFlags().containsKey(flagKey)) {
            return properties.isEnabled(flagKey);
        }
        return Boolean.TRUE;
    }

    /** Reloads the override snapshot when stale; empty when Redis is absent/off. */
    private Mono<Map<String, String>> refreshWhenStale() {
        ReactiveStringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return Mono.empty();
        }
        long now = System.nanoTime();
        if (now - lastLoadNanos.get() <= CACHE_TTL.toNanos()) {
            return Mono.just(Map.copyOf(overrides));
        }
        return redis.<String, String>opsForHash().entries(OVERRIDES_HASH)
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .timeout(Duration.ofSeconds(1))
                .doOnNext(fresh -> {
                    overrides.keySet().retainAll(fresh.keySet());
                    overrides.putAll(fresh);
                    lastLoadNanos.set(System.nanoTime());
                })
                .onErrorResume(ex -> {
                    // Fail-open: keep serving from the previous snapshot/defaults.
                    log.warn("EDGE_FLAG_REFRESH_FAILED | error={}", ex.getMessage());
                    lastLoadNanos.set(System.nanoTime());
                    return Mono.just(Map.copyOf(overrides));
                });
    }

    /** Test hook: seed an override value ({@code null} removes it). */
    void primeOverride(String key, String value) {
        if (value == null) {
            overrides.remove(key);
        } else {
            overrides.put(key, value);
        }
        lastLoadNanos.set(System.nanoTime());
    }
}
