package com.bhukkad.cache;

import com.bhukkad.dto.response.ServiceabilityResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

/**
 * Read-through cache for delivery serviceability checks.
 *
 * <p>{@code GET /serviceability/check} is called repeatedly while a customer
 * edits their cart or pans the map, and each call loads the restaurant with its
 * address and zone graph before running the point-in-polygon and fee
 * calculation. The verdict is a pure function of the request parameters, so the
 * whole {@link ServiceabilityResponse} is cached and a hit short-circuits the
 * restaurant load entirely rather than only skipping the zone maths.
 *
 * <p>The cache key includes every input that can change the verdict: restaurant,
 * drop latitude/longitude and cart subtotal (which drives free-delivery
 * thresholds and fee slabs). Coordinates are not rounded, so keys are only
 * shared between callers reporting the exact same position - which is the common
 * case for a stationary device re-checking after a cart edit.
 *
 * <p>The TTL is deliberately short because surge multipliers and zone
 * activation change intraday. All operations delegate to
 * {@link RedisCacheService}, which swallows and logs its own failures, so a
 * Redis outage degrades to direct computation instead of an error.
 */
@Service
@RequiredArgsConstructor
public class ServiceabilityCacheService {

    private final RedisCacheService cacheService;

    /** Short TTL: surge and zone toggles must take effect within a minute. */
    @Value("${cache.ttl.serviceability:60}")
    private long serviceabilityTtlSeconds;

    /**
     * Returns the serviceability verdict for the given request parameters,
     * invoking {@code loader} only on a cache miss.
     *
     * <p>Concurrent misses for the same key collapse onto a single {@code loader}
     * invocation via the Redis lock held by
     * {@link RedisCacheService#getOrCompute}.
     *
     * @param restaurantId restaurant being ordered from
     * @param latitude     drop-off latitude
     * @param longitude    drop-off longitude
     * @param subtotal     current cart subtotal, affects fee and free-delivery slabs
     * @param loader       supplier that loads the restaurant and computes the verdict
     * @return the cached or freshly computed verdict
     */
    public ServiceabilityResponse getServiceability(Long restaurantId,
                                                    double latitude,
                                                    double longitude,
                                                    double subtotal,
                                                    Supplier<ServiceabilityResponse> loader) {
        return cacheService.getOrCompute(
                CacheKeyGenerator.serviceability(restaurantId, latitude, longitude, subtotal),
                ServiceabilityResponse.class,
                serviceabilityTtlSeconds,
                loader);
    }

    /**
     * Drops every cached verdict for a restaurant.
     *
     * <p>Call this when a restaurant's address or delivery zone configuration
     * changes so stale verdicts are not served for the remainder of the TTL.
     *
     * @param restaurantId restaurant whose verdicts should be discarded
     */
    public void invalidateRestaurant(Long restaurantId) {
        cacheService.deletePattern(CacheConstants.SERVICEABILITY + CacheConstants.KEY_SEPARATOR
                + "restaurant:" + restaurantId);
    }
}
