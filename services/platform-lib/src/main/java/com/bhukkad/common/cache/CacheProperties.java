package com.bhukkad.common.cache;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Global cache tunables shared by {@link RedisCacheService} and
 * {@link LocalCacheService}. Every field has a production-safe default so
 * existing deployments are unaffected unless the operator opts in.
 */
@Data
@ConfigurationProperties(prefix = "app.cache")
public class CacheProperties {

    /**
     * TTL (seconds) for the distributed single-flight lock acquired in
     * {@link RedisCacheService#computeUnderDistributedLock}. Must be longer
     * than the P99 supplier latency plus a safety margin; too short and the
     * lock expires while the supplier is still running, allowing duplicate
     * computation.
     */
    private long lockTtlSeconds = 30;

    /**
     * Upper bound for the in-JVM single-flight map
     * ({@code RedisCacheService.inFlightLoads}). When the map exceeds this
     * size, completed futures are evicted before registering a new flight.
     * This prevents a memory leak under sustained high-cardinality workloads
     * where every request misses a unique cache key.
     */
    private int maxInFlightEntries = 10_000;
}
