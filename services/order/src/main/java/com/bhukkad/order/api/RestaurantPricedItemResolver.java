package com.bhukkad.order.api;

import com.bhukkad.order.client.RestaurantClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Resolves the authoritative name + price for a menu item from the restaurant
 * service. Used by the canonical cart/order surfaces so client-supplied
 * prices can never enter the money path.
 *
 * <p>Results are cached briefly (60s, small bounded map) because the same hot
 * menu items are priced repeatedly under load; a TTL-style lazy invalidation
 * keeps the code dependency-free.</p>
 */
@Component
public class RestaurantPricedItemResolver {

    public record PricedItem(Long menuItemId, String name, BigDecimal price) {}

    private static final long CACHE_TTL_MILLIS = 60_000L;
    private static final int CACHE_MAX_ENTRIES = 5_000;

    private final RestaurantClient restaurantClient;
    private final java.util.concurrent.ConcurrentMap<Long, CachedItem> cache =
            new java.util.concurrent.ConcurrentHashMap<>();

    private record CachedItem(PricedItem item, long expiresAtMillis) {}

    public RestaurantPricedItemResolver(RestaurantClient restaurantClient) {
        this.restaurantClient = restaurantClient;
    }

    public PricedItem resolve(Long menuItemId) {
        long now = System.currentTimeMillis();
        CachedItem cached = cache.get(menuItemId);
        if (cached != null && now < cached.expiresAtMillis()) {
            return cached.item();
        }
        Map<String, Object> remote;
        try {
            remote = restaurantClient.getMenuItem(menuItemId)
                    .timeout(java.time.Duration.ofSeconds(3))
                    .block(java.time.Duration.ofSeconds(4));
        } catch (RuntimeException meshFailure) {
            // A downstream outage must NOT look like a missing item: surface
            // 503 so callers can retry (matches GlobalExceptionHandler).
            throw new com.bhukkad.common.error.UpstreamUnavailableException(
                    "restaurant", meshFailure);
        }
        if (remote == null || remote.isEmpty()) {
            // A downstream outage must NOT look like a missing item.
            throw new com.bhukkad.common.error.BusinessException(
                    "Menu item is temporarily unavailable: " + menuItemId);
        }
        Object priceObj = remote.get("price");
        if (priceObj == null) {
            // A missing price must never default to 0 (free item).
            throw new com.bhukkad.common.error.BusinessException(
                    "Menu item has no purchasable price: " + menuItemId);
        }
        BigDecimal price;
        try {
            price = new BigDecimal(String.valueOf(priceObj));
        } catch (NumberFormatException e) {
            throw new com.bhukkad.common.error.BusinessException(
                    "Menu item has an invalid price: " + menuItemId);
        }
        Object nameObj = remote.get("name");
        PricedItem item = new PricedItem(menuItemId,
                nameObj == null ? "Item " + menuItemId : String.valueOf(nameObj), price);
        if (cache.size() > CACHE_MAX_ENTRIES) {
            cache.clear();
        }
        cache.put(menuItemId, new CachedItem(item, now + CACHE_TTL_MILLIS));
        return item;
    }
}
