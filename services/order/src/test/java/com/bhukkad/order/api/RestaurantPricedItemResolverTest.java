package com.bhukkad.order.api;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.UpstreamUnavailableException;
import com.bhukkad.order.client.RestaurantClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PERF-3: checkout-chord consolidation. A cart of N cold items must cost ONE
 * batch restaurant call (was N serial {@code getMenuItem} RTTs) and the
 * cache must evict in segments (Caffeine) instead of the old wholesale
 * {@code clear()} stampede generator.
 */
@ExtendWith(MockitoExtension.class)
class RestaurantPricedItemResolverTest {

    @Mock private RestaurantClient restaurantClient;

    private static Map<String, Object> item(long id, String name, String price) {
        return Map.of("id", id, "restaurantId", id * 7, "name", name,
                "price", new java.math.BigDecimal(price), "available", true);
    }

    @Test
    void resolveAll_tenItemCart_issuesExactlyOneBatchCall() {
        RestaurantPricedItemResolver resolver = new RestaurantPricedItemResolver(restaurantClient);
        List<Long> ids = new ArrayList<>();
        List<Map<String, Object>> payload = new ArrayList<>();
        for (long i = 1; i <= 10; i++) {
            ids.add(i);
            payload.add(item(i, "Item " + i, "99.00"));
        }
        when(restaurantClient.getMenuItems(anyCollection())).thenReturn(Mono.just(payload));

        Map<Long, RestaurantPricedItemResolver.PricedItem> priced = resolver.resolveAll(ids);

        verify(restaurantClient, times(1)).getMenuItems(anyCollection());
        assertThat(priced).hasSize(10);
        assertThat(priced.get(1L).price()).isEqualByComparingTo("99.00");
        assertThat(priced.get(1L).name()).isEqualTo("Item 1");
    }

    @Test
    void resolveAll_secondCallOnSameItems_servedFromCacheZeroNetwork() {
        RestaurantPricedItemResolver resolver = new RestaurantPricedItemResolver(restaurantClient);
        when(restaurantClient.getMenuItems(anyCollection()))
                .thenReturn(Mono.just(List.of(item(1L, "A", "10.00"), item(2L, "B", "20.00"))));

        resolver.resolveAll(List.of(1L, 2L));
        resolver.resolveAll(List.of(1L, 2L));

        verify(restaurantClient, times(1)).getMenuItems(anyCollection());
    }

    @Test
    void resolveAll_duplicateAndNullIds_collapseBeforeTheCall() {
        RestaurantPricedItemResolver resolver = new RestaurantPricedItemResolver(restaurantClient);
        when(restaurantClient.getMenuItems(anyCollection())).thenReturn(Mono.just(List.of(item(1L, "A", "1.00"))));

        var priced = resolver.resolveAll(java.util.Arrays.asList(1L, 1L, null));

        assertThat(priced).containsOnlyKeys(1L);
        verify(restaurantClient).getMenuItems(List.of(1L));
    }

    @Test
    void resolveAll_emptyRequest_neverCallsRestaurant() {
        RestaurantPricedItemResolver resolver = new RestaurantPricedItemResolver(restaurantClient);

        assertThat(resolver.resolveAll(List.of())).isEmpty();

        verify(restaurantClient, never()).getMenuItems(anyCollection());
    }

    @Test
    void resolveAll_upstreamFailure_surfaces503_notMissingItem() {
        RestaurantPricedItemResolver resolver = new RestaurantPricedItemResolver(restaurantClient);
        when(restaurantClient.getMenuItems(anyCollection()))
                .thenReturn(Mono.error(new RuntimeException("connection refused")));

        assertThatThrownBy(() -> resolver.resolveAll(List.of(5L)))
                .isInstanceOf(UpstreamUnavailableException.class);
    }

    @Test
    void resolveAll_missingId_throwsUnavailableBusinessError() {
        RestaurantPricedItemResolver resolver = new RestaurantPricedItemResolver(restaurantClient);
        when(restaurantClient.getMenuItems(anyCollection())).thenReturn(Mono.just(List.of()));

        assertThatThrownBy(() -> resolver.resolveAll(List.of(404L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("temporarily unavailable: 404");
    }

    @Test
    void resolveAll_nullPrice_neverDefaultsToFree() {
        RestaurantPricedItemResolver resolver = new RestaurantPricedItemResolver(restaurantClient);
        when(restaurantClient.getMenuItems(anyCollection())).thenReturn(
                Mono.just(List.of(Map.of("id", 9L, "name", "Ghost", "available", true))));

        assertThatThrownBy(() -> resolver.resolveAll(List.of(9L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("no purchasable price");
    }

    @Test
    void resolve_singleItem_delegatesToOneElementBatchCall() {
        RestaurantPricedItemResolver resolver = new RestaurantPricedItemResolver(restaurantClient);
        when(restaurantClient.getMenuItems(anyCollection())).thenReturn(Mono.just(List.of(item(3L, "C", "5.50"))));

        RestaurantPricedItemResolver.PricedItem priced = resolver.resolve(3L);

        assertThat(priced.menuItemId()).isEqualTo(3L);
        verify(restaurantClient, times(1)).getMenuItems(anyCollection());
    }
}
