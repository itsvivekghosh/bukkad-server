package com.bhukkad.cache;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CacheKeyGeneratorBatchTest {

    @Test
    void menuItemsByIds_generatesCorrectKey() {
        String key = CacheKeyGenerator.menuItemsByIds(List.of(1L, 2L, 3L));
        assertTrue(key.startsWith("menu-item-list:ids:"));
        assertTrue(key.contains("1"));
        assertTrue(key.contains("2"));
        assertTrue(key.contains("3"));
    }

    @Test
    void menuItemsByIds_singleId() {
        String key = CacheKeyGenerator.menuItemsByIds(List.of(42L));
        assertEquals("menu-item-list:ids:42", key);
    }

    @Test
    void restaurantsByIds_generatesCorrectKey() {
        String key = CacheKeyGenerator.restaurantsByIds(List.of(10L, 20L));
        assertTrue(key.startsWith("restaurant-list:ids:"));
        assertTrue(key.contains("10"));
        assertTrue(key.contains("20"));
    }

    @Test
    void restaurantsByIds_singleId() {
        String key = CacheKeyGenerator.restaurantsByIds(List.of(99L));
        assertEquals("restaurant-list:ids:99", key);
    }
}