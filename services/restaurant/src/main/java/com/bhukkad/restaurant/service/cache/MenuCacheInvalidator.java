package com.bhukkad.restaurant.service.cache;

import com.bhukkad.common.cache.CacheInvalidationService;
import com.bhukkad.common.cache.RedisCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Coordinated eviction of the PERF-3 restaurant read caches (feed projection,
 * per-restaurant menu snapshot, per-id rendered menu items).
 *
 * <p>Eviction is two-pronged and best-effort:
 * <ul>
 *   <li>{@link RedisCacheService#delete(String)} removes the L2 entry, evicts
 *       the local L1 copy and publishes the distributed
 *       {@code CacheInvalidatedEvent} so peer pods evict too;</li>
 *   <li>{@link CacheInvalidationService#publishInvalidation(String)} is kept as
 *       the second (local-cache) pub/sub channel — it is cheap and matches the
 *       task contract to publish invalidation on mutation.</li>
 * </ul>
 * Both services are optional beans ({@code @ConditionalOnBean} on the Redis
 * template), so the no-op fallback keeps the app bootable without Redis.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MenuCacheInvalidator {

    private final ObjectProvider<RedisCacheService> redisCache;
    private final ObjectProvider<CacheInvalidationService> localInvalidation;

    /** Restaurant activation/creation changed — the composite home feed is stale. */
    public void invalidateFeed() {
        evictAfterCommit(RestaurantCacheKeys.FEED);
    }

    /** A menu mutation landed for this restaurant — snapshot + affected item rows. */
    public void invalidateMenu(Long restaurantId) {
        if (restaurantId != null) {
            evictAfterCommit(RestaurantCacheKeys.menuSnapshot(restaurantId));
        }
    }

    /** A single menu item changed / was removed — its per-id render cache is stale. */
    public void invalidateMenuItem(Long menuItemId) {
        if (menuItemId != null) {
            evictAfterCommit(RestaurantCacheKeys.menuItem(menuItemId));
        }
    }

    private void evictAfterCommit(String key) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            // Mutation services run inside @Transactional commits; evicting
            // before the commit would let a concurrent read repopulate the
            // cache from the pre-commit state.
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .registerSynchronization(
                            new org.springframework.transaction.support.TransactionSynchronization() {
                                @Override
                                public void afterCommit() {
                                    evict(key);
                                }
                            });
            return;
        }
        evict(key);
    }

    private void evict(String key) {
        try {
            RedisCacheService cache = redisCache.getIfAvailable();
            if (cache != null) {
                cache.delete(key);
            }
            CacheInvalidationService invalidation = localInvalidation.getIfAvailable();
            if (invalidation != null) {
                invalidation.publishInvalidation(key);
            }
            log.debug("MENU_CACHE_INVALIDATED key={} redis={} local={}",
                    key, cache != null, invalidation != null);
        } catch (RuntimeException ex) {
            // Invalidation is best-effort: the TTL bounds staleness even if the
            // eviction publish is lost. Never fail the write that triggered it.
            log.warn("MENU_CACHE_INVALIDATION_FAILED key={} error={}", key, ex.getMessage());
        }
    }
}
