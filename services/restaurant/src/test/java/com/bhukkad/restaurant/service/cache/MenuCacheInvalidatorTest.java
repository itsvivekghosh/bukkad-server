package com.bhukkad.restaurant.service.cache;

import com.bhukkad.common.cache.CacheInvalidationService;
import com.bhukkad.common.cache.RedisCacheService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * PERF-3: menu/feed cache eviction — L2 delete + distributed publish + local
 * pub/sub invalidation, deferred to after-commit when a transaction is active
 * (so a concurrent reader cannot reload the pre-commit row into the cache).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MenuCacheInvalidatorTest {

    @Mock private RedisCacheService redisCacheService;
    @Mock private CacheInvalidationService cacheInvalidationService;

    private MenuCacheInvalidator invalidator(boolean withBeans) {
        ObjectProvider<RedisCacheService> redis = provider(
                withBeans ? redisCacheService : null);
        ObjectProvider<CacheInvalidationService> local = provider(
                withBeans ? cacheInvalidationService : null);
        return new MenuCacheInvalidator(redis, local);
    }

    private static <T> ObjectProvider<T> provider(T bean) {
        return new com.bhukkad.restaurant.testsupport.FixedObjectProvider<>(bean);
    }

    @Test
    void invalidateFeed_deletesL2AndPublishesToPeers() {
        invalidator(true).invalidateFeed();

        verify(redisCacheService).delete(RestaurantCacheKeys.FEED);
        verify(cacheInvalidationService).publishInvalidation("feed:v1");
    }

    @Test
    void keyScheme_matchesContract() {
        assertThat(RestaurantCacheKeys.menuSnapshot(42L)).isEqualTo("menu:restaurant:42");
        assertThat(RestaurantCacheKeys.menuItem(8L)).isEqualTo("menu:item:8");
        assertThat(RestaurantCacheKeys.FEED_TTL_SECONDS).isEqualTo(45L);
        assertThat(RestaurantCacheKeys.MENU_SNAPSHOT_TTL_SECONDS).isEqualTo(300L);
        assertThat(RestaurantCacheKeys.MENU_ITEM_TTL_SECONDS).isEqualTo(60L);
    }

    @Test
    void evictionDuringActiveTransaction_deferredUntilAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            invalidator(true).invalidateMenu(7L);

            // Commit still pending: nothing evicted yet...
            verify(redisCacheService, never()).delete(any());

            // ...the registered afterCommit hook performs it.
            List<TransactionSynchronization> syncs =
                    TransactionSynchronizationManager.getSynchronizations();
            assertThat(syncs).isNotEmpty();
            TransactionSynchronization registered = syncs.get(syncs.size() - 1);
            verify(redisCacheService, never()).delete(any());
            // Simulating eviction directly would double-publish; assert via the
            // actual registered sync instead:
            boolean evictedOnCommit = false;
            for (TransactionSynchronization sync : syncs) {
                sync.afterCommit();
                evictedOnCommit = true;
            }
            assertThat(evictedOnCommit).isTrue();
            verify(redisCacheService).delete("menu:restaurant:7");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void evictionOutsideTransaction_appliesImmediately() {
        invalidator(true).invalidateMenuItem(3L);
        verify(redisCacheService).delete("menu:item:3");
    }

    @Test
    void nullSafe_noRedisBeans_neverThrowsAndKeysGuarded() {
        assertThatCode(() -> invalidator(false).invalidateFeed()).doesNotThrowAnyException();
        assertThatCode(() -> invalidator(true).invalidateMenu(null)).doesNotThrowAnyException();
        assertThatCode(() -> invalidator(true).invalidateMenuItem(null)).doesNotThrowAnyException();
        verify(redisCacheService, never()).delete(any());
    }

    @Test
    void redisFailure_neverFailsTheMutationThatTriggeredIt() {
        org.mockito.Mockito.doThrow(new RuntimeException("redis down"))
                .when(redisCacheService).delete("feed:v1");

        assertThatCode(() -> invalidator(true).invalidateFeed()).doesNotThrowAnyException();
    }
}
