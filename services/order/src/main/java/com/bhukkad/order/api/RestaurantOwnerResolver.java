package com.bhukkad.order.api;

import com.bhukkad.common.error.UpstreamUnavailableException;
import com.bhukkad.order.infrastructure.client.RestaurantClient;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Resolves the owner of a restaurant from the restaurant service, with a
 * short-lived positive cache (audit HIGH-IDOR-3). Used by the owner-facing
 * order surface to verify that a RESTAURANT_OWNER principal actually owns the
 * addressed restaurant — the check fires on every restaurant-scoped order
 * call, so the 60 s Caffeine cache (same policy as
 * {@link RestaurantPricedItemResolver}) keeps the mesh RTT off the hot path
 * without meaningfully weakening the check.
 *
 * <p>Error semantics: a restaurant the oracle does not know (404) and an
 * oracle outage both fail CLOSED — the guard turns them into an authorization
 * denial, never into a pass. Only a confirmed owner match passes.</p>
 */
@Component
public class RestaurantOwnerResolver {

    private static final Duration CACHE_TTL = Duration.ofSeconds(60);
    private static final long CACHE_MAX_ENTRIES = 10_000L;
    /** Outer bound for the blocking oracle read (client timeout is 3 s). */
    private static final Duration BLOCK_BUDGET = Duration.ofSeconds(5);

    private final RestaurantClient restaurantClient;
    private final Cache<Long, Long> ownerCache = Caffeine.newBuilder()
            .maximumSize(CACHE_MAX_ENTRIES)
            .expireAfterWrite(CACHE_TTL)
            .build();

    public RestaurantOwnerResolver(RestaurantClient restaurantClient) {
        this.restaurantClient = restaurantClient;
    }

    /**
     * @return the restaurant's owner id, or {@code null} when the restaurant
     *         does not exist (callers must treat that as not-owned)
     */
    public Long ownerIdOf(Long restaurantId) {
        Long cached = ownerCache.getIfPresent(restaurantId);
        if (cached != null) {
            return cached;
        }
        Long ownerId;
        try {
            ownerId = restaurantClient.getRestaurantOwnerId(restaurantId)
                    .block(BLOCK_BUDGET);
        } catch (RuntimeException meshFailure) {
            // An outage must never look like "not owned" OR like "owned":
            // surface 503 and let the guard fail closed (matches the
            // GlobalExceptionHandler mapping).
            throw new UpstreamUnavailableException("restaurant", meshFailure);
        }
        if (ownerId != null) {
            ownerCache.put(restaurantId, ownerId);
        }
        return ownerId;
    }
}
