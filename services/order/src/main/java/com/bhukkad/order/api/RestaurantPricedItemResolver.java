package com.bhukkad.order.api;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.UpstreamUnavailableException;
import com.bhukkad.order.client.RestaurantClient;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the authoritative name + price for menu items from the restaurant
 * service. Used by the canonical cart/order surfaces so client-supplied
 * prices can never enter the money path.
 *
 * <p>PERF-3: results are cached briefly (60 s, Caffeine
 * {@code maximumSize(10_000)}) — the previous hand-rolled map {@code clear()}
 * >5000 entries wholesale, which evicted every hot item at once and became a
 * periodic stampede generator; Caffeine now evicts segments (LRU-ish) with the
 * same bound. {@link #resolveAll(Collection)} hits the restaurant batch
 * surface ({@code GET /api/v1/menu/items?ids=…}) with ONE service-to-service
 * call per set of cold ids, so an N-item cart no longer pays N serial RTTs.
 * The per-call 3 s response timeout of the underlying client is unchanged
 * (client unification lands separately in PERF-5).</p>
 */
@Component
public class RestaurantPricedItemResolver {

    public record PricedItem(Long menuItemId, String name, BigDecimal price) {}

    private static final Duration CACHE_TTL = Duration.ofSeconds(60);
    private static final long CACHE_MAX_ENTRIES = 10_000L;
    /** Outer bound for the batch fetch (client already applies a 3 s per-call timeout). */
    private static final Duration BATCH_BLOCK_BUDGET = Duration.ofSeconds(5);

    private final RestaurantClient restaurantClient;
    private final Cache<Long, PricedItem> cache = Caffeine.newBuilder()
            .maximumSize(CACHE_MAX_ENTRIES)
            .expireAfterWrite(CACHE_TTL)
            .build();

    public RestaurantPricedItemResolver(RestaurantClient restaurantClient) {
        this.restaurantClient = restaurantClient;
    }

    /** Resolve one item by id (cached; a cold single id costs one batch call of size 1). */
    public PricedItem resolve(Long menuItemId) {
        return resolveAll(List.of(menuItemId)).get(menuItemId);
    }

    /**
     * Resolve a set of items with at most ONE restaurant batch call for all
     * cache-miss ids. Returns a map keyed by the requested ids (duplicate and
     * null ids collapse; insertion order of first occurrence is kept).
     *
     * <p>Error semantics match {@link #resolve(Long)}: restaurant outage →
     * {@link UpstreamUnavailableException} (never a fake "missing item"); an
     * id the restaurant does not return → {@link BusinessException}.</p>
     */
    public Map<Long, PricedItem> resolveAll(Collection<Long> menuItemIds) {
        List<Long> requested = new ArrayList<>(
                new java.util.LinkedHashSet<>(menuItemIds == null ? List.<Long>of() : menuItemIds));
        requested.remove(null);
        Map<Long, PricedItem> result = new LinkedHashMap<>();
        List<Long> cold = new ArrayList<>();
        for (Long id : requested) {
            PricedItem hit = cache.getIfPresent(id);
            if (hit != null) {
                result.put(id, hit);
            } else {
                cold.add(id);
            }
        }
        if (cold.isEmpty()) {
            return result;
        }
        for (Map.Entry<Long, PricedItem> entry : fetchBatch(cold).entrySet()) {
            cache.put(entry.getKey(), entry.getValue());
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    /** One restaurant batch call; every cold id must come back validated. */
    private Map<Long, PricedItem> fetchBatch(List<Long> ids) {
        List<Map<String, Object>> remote;
        try {
            remote = restaurantClient.getMenuItems(ids)
                    .block(BATCH_BLOCK_BUDGET);
        } catch (RuntimeException meshFailure) {
            // A downstream outage must NOT look like a missing item: surface
            // 503 so callers can retry (matches GlobalExceptionHandler).
            throw new UpstreamUnavailableException("restaurant", meshFailure);
        }
        Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();
        if (remote != null) {
            for (Map<String, Object> item : remote) {
                Long id = parseId(item.get("id"));
                if (id != null) {
                    byId.put(id, item);
                }
            }
        }
        Map<Long, PricedItem> priced = new LinkedHashMap<>();
        for (Long id : ids) {
            Map<String, Object> item = byId.get(id);
            if (item == null || item.isEmpty()) {
                // A downstream outage must NOT look like a missing item.
                throw new BusinessException("Menu item is temporarily unavailable: " + id);
            }
            priced.put(id, toPricedItem(id, item));
        }
        return priced;
    }

    private static Long parseId(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(raw));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static PricedItem toPricedItem(Long menuItemId, Map<String, Object> item) {
        Object priceObj = item.get("price");
        if (priceObj == null) {
            // A missing price must never default to 0 (free item).
            throw new BusinessException("Menu item has no purchasable price: " + menuItemId);
        }
        BigDecimal price;
        try {
            price = new BigDecimal(String.valueOf(priceObj));
        } catch (NumberFormatException e) {
            throw new BusinessException("Menu item has an invalid price: " + menuItemId);
        }
        Object nameObj = item.get("name");
        return new PricedItem(menuItemId,
                nameObj == null ? "Item " + menuItemId : String.valueOf(nameObj), price);
    }
}
