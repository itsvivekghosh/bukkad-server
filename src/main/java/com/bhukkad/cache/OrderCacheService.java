package com.bhukkad.cache;

import com.bhukkad.dto.response.OrderResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Caching facade for order reads.
 *
 * <p>Follows the project's manual caching convention: this service owns the key
 * shapes and TTLs for the order domain and delegates all storage concerns to
 * {@link RedisCacheService}, which swallows and logs its own failures so a Redis
 * outage degrades to a database read rather than an error.
 *
 * <p>The tracking snapshot is deliberately <strong>customer-scoped</strong>. Order
 * tracking is private data served only to the order's owner, so the cache key
 * includes the authenticated customer id; a shared key would allow an entry
 * populated by the owner to be served to another caller, bypassing the ownership
 * check performed on the database path.
 */
@Service
@RequiredArgsConstructor
public class OrderCacheService {

    private final RedisCacheService cacheService;

    /** TTL for live tracking snapshots. Short by design: order state changes often. */
    @Value("${cache.ttl.order-track:30}")
    private long orderTrackTtlSeconds;

    /**
     * Reads a cached tracking snapshot for one order as seen by one customer.
     *
     * @param orderId    the order being tracked
     * @param customerId the authenticated customer requesting the snapshot
     * @return the cached snapshot, or empty on miss (including when Redis is down)
     */
    public Optional<OrderResponse> getTrackedOrder(Long orderId, Long customerId) {
        return cacheService.get(CacheKeyGenerator.orderTrack(orderId, customerId), OrderResponse.class);
    }

    /**
     * Stores a tracking snapshot under a customer-scoped key.
     *
     * @param orderId    the order being tracked
     * @param customerId the authenticated customer the snapshot was rendered for
     * @param response   the snapshot to cache
     */
    public void cacheTrackedOrder(Long orderId, Long customerId, OrderResponse response) {
        cacheService.set(CacheKeyGenerator.orderTrack(orderId, customerId), response, orderTrackTtlSeconds);
    }

    /**
     * Evicts every cached view of an order after a state change.
     *
     * <p>Tracking snapshots are removed by pattern rather than by exact key because
     * the key is customer-scoped, so an exact delete would leave the owner's entry
     * stale until its TTL expired.
     *
     * @param orderId      the order that changed
     * @param customerId   owning customer, may be {@code null} to skip list eviction
     * @param restaurantId fulfilling restaurant, may be {@code null} to skip queue eviction
     */
    public void invalidateOrder(Long orderId, Long customerId, Long restaurantId) {
        cacheService.delete(CacheKeyGenerator.order(orderId));
        cacheService.deletePattern(CacheKeyGenerator.orderTrackPattern(orderId));
        if (customerId != null) {
            cacheService.deletePattern(CacheKeyGenerator.orderPattern(customerId));
        }
        if (restaurantId != null) {
            cacheService.delete(CacheKeyGenerator.kitchenQueue(restaurantId));
        }
    }
}
