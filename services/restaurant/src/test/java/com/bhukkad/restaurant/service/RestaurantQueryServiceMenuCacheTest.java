package com.bhukkad.restaurant.service;

import com.bhukkad.common.cache.RedisCacheService;
import com.bhukkad.restaurant.api.MenuSnapshot;
import com.bhukkad.restaurant.domain.MenuItem;
import com.bhukkad.restaurant.domain.MenuItemRepository;
import com.bhukkad.restaurant.domain.Restaurant;
import com.bhukkad.restaurant.domain.RestaurantRepository;
import com.bhukkad.restaurant.service.cache.RestaurantCacheKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PERF-3 item 3: {@code GET /api/v1/restaurants/{id}/menu} (and the internal
 * snapshot contract sharing the service) is served from
 * {@code menu:restaurant:<id>} (300 s) through RedisCacheService; the cached
 * value is the canonical snapshot JSON, so what the order client parses stays
 * byte-identical to the live DTO.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RestaurantQueryServiceMenuCacheTest {

    @Mock private RestaurantRepository restaurantRepository;
    @Mock private MenuItemRepository menuItemRepository;
    @Mock private RedisCacheService redisCacheService;

    private final Map<String, Object> backing = new HashMap<>();
    private RestaurantQueryService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void wire() {
        backing.clear();
        when(redisCacheService.getOrCompute(anyString(), eq(String.class), anyLong(), any(Supplier.class)))
                .thenAnswer(inv -> {
                    String key = inv.getArgument(0);
                    if (backing.containsKey(key)) {
                        return backing.get(key);
                    }
                    Object loaded = ((Supplier<Object>) inv.getArgument(3)).get();
                    backing.put(key, loaded);
                    return loaded;
                });

        Restaurant restaurant = new Restaurant();
        restaurant.setId(5L);
        restaurant.setName("Tiffins");
        when(restaurantRepository.findById(5L)).thenReturn(Optional.of(restaurant));
        MenuItem item = new MenuItem();
        item.setId(11L);
        item.setName("Idli");
        item.setPrice(new BigDecimal("19.50"));
        item.setIsAvailable(true);
        when(menuItemRepository.findByRestaurantIdAndIsAvailableTrue(5L)).thenReturn(List.of(item));

        ObjectProvider<RedisCacheService> provider = new com.bhukkad.restaurant.testsupport.FixedObjectProvider<>(redisCacheService);
        service = new RestaurantQueryService(restaurantRepository, menuItemRepository,
                provider, new ObjectMapper());
    }

    @Test
    void menuSnapshot_cachedUnderMenuRestaurantKey() {
        MenuSnapshot snapshot = service.menuSnapshot(5L);

        assertThat(snapshot.restaurantId()).isEqualTo(5L);
        assertThat(snapshot.items()).hasSize(1);
        verify(redisCacheService).getOrCompute(
                eq(RestaurantCacheKeys.menuSnapshot(5L)),
                eq(String.class),
                eq(RestaurantCacheKeys.MENU_SNAPSHOT_TTL_SECONDS),
                any(Supplier.class));

        // Second call within TTL: no DB access — served from the cache.
        service.menuSnapshot(5L);
        verify(restaurantRepository, times(1)).findById(5L);
        verify(menuItemRepository, times(1)).findByRestaurantIdAndIsAvailableTrue(5L);
    }

    @Test
    void menuSnapshot_cachedValueIsCanonicalJsonWithMoneyScale() {
        MenuSnapshot first = service.menuSnapshot(5L);

        String cached = (String) backing.get(RestaurantCacheKeys.menuSnapshot(5L));
        assertThat(cached).isNotNull();
        // The cached payload is the canonical snapshot JSON (BigDecimal scale kept).
        assertThat(cached).contains("\"restaurantId\":5");
        assertThat(cached).contains("19.50");

        MenuSnapshot warm = service.menuSnapshot(5L); // served from the same string
        assertThat(warm).isEqualTo(first);
    }

    @Test
    void menuSnapshot_withoutRedis_fallsBackToDirectLoad() {
        ObjectProvider<RedisCacheService> none = new com.bhukkad.restaurant.testsupport.FixedObjectProvider<>(null);
        RestaurantQueryService direct = new RestaurantQueryService(
                restaurantRepository, menuItemRepository, none, new ObjectMapper());

        MenuSnapshot snapshot = direct.menuSnapshot(5L);

        assertThat(snapshot.restaurantId()).isEqualTo(5L);
        assertThat(snapshot.items()).hasSize(1);
        // Behavior preserved from before PERF-3: uncached direct load.
        verify(restaurantRepository, times(1)).findById(5L);
    }
}
