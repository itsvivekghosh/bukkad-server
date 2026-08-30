package com.bhukkad.cache;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CacheKeyGeneratorHeavyTrafficTest {

    @Test
    void menuItemsByIds_sorted_stableKey() {
        String key1 = CacheKeyGenerator.menuItemsByIds(List.of(2L, 1L, 3L));
        String key2 = CacheKeyGenerator.menuItemsByIds(List.of(1L, 2L, 3L));
        String key3 = CacheKeyGenerator.menuItemsByIds(List.of(3L, 2L, 1L));
        assertThat(key1).isEqualTo(key2).isEqualTo(key3);
        assertThat(key1).isEqualTo("menu-item-list:ids:1,2,3");
    }

    @Test
    void restaurantsByIds_sorted_stableKey() {
        String keyA = CacheKeyGenerator.restaurantsByIds(List.of(100L, 5L));
        String keyB = CacheKeyGenerator.restaurantsByIds(List.of(5L, 100L));
        assertThat(keyA).isEqualTo(keyB);
        assertThat(keyA).isEqualTo("restaurant-list:ids:5,100");
    }

    @Test
    void restaurantNearby_quantized_sameGridSameKey() {
        String k1 = CacheKeyGenerator.restaurantNearby(12.9101, 77.6401, 5.001);
        String k2 = CacheKeyGenerator.restaurantNearby(12.9102, 77.6402, 5.002);
        String k3 = CacheKeyGenerator.restaurantNearby(12.9104, 77.6404, 5.003);
        // 3-decimal quantize -> same grid (100m), 2-decimal radius -> same
        assertThat(k1).isEqualTo(k2).isEqualTo(k3);
    }

    @Test
    void restaurantNearby_differentGrid_differentKey() {
        String near = CacheKeyGenerator.restaurantNearby(12.910, 77.640, 5.00);
        String far = CacheKeyGenerator.restaurantNearby(12.920, 77.640, 5.00);
        assertThat(near).isNotEqualTo(far);
    }

    @Test
    void serviceability_quantized() {
        String s1 = CacheKeyGenerator.serviceability(1L, 12.91012, 77.64012, 250.001);
        String s2 = CacheKeyGenerator.serviceability(1L, 12.91014, 77.64014, 250.003);
        assertThat(s1).isEqualTo(s2);
    }

    @Test
    void serviceability_differentSubtotal_differentKey() {
        String s1 = CacheKeyGenerator.serviceability(1L, 12.910, 77.640, 100.00);
        String s2 = CacheKeyGenerator.serviceability(1L, 12.910, 77.640, 200.00);
        assertThat(s1).isNotEqualTo(s2);
    }

    @Test
    void menuItemsByIds_singleElement() {
        String key = CacheKeyGenerator.menuItemsByIds(List.of(42L));
        assertThat(key).isEqualTo("menu-item-list:ids:42");
    }
}
