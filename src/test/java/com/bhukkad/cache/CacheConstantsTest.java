package com.bhukkad.cache;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.*;

class CacheConstantsTest {

    @Test
    void cacheNames() {
        assertEquals("restaurant", CacheConstants.RESTAURANT);
        assertEquals("restaurant-list", CacheConstants.RESTAURANT_LIST);
        assertEquals("restaurant-search", CacheConstants.RESTAURANT_SEARCH);
        assertEquals("restaurant-filter", CacheConstants.RESTAURANT_FILTER);
        assertEquals("menu-item", CacheConstants.MENU_ITEM);
        assertEquals("menu-item-list", CacheConstants.MENU_ITEM_LIST);
        assertEquals("menu-category", CacheConstants.MENU_CATEGORY);
        assertEquals("menu-category-list", CacheConstants.MENU_CATEGORY_LIST);
        assertEquals("cuisine", CacheConstants.CUISINE);
        assertEquals("cuisine-list", CacheConstants.CUISINE_LIST);
        assertEquals("user-profile", CacheConstants.USER_PROFILE);
        assertEquals("cart", CacheConstants.CART);
        assertEquals("order", CacheConstants.ORDER);
        assertEquals("order-list", CacheConstants.ORDER_LIST);
        assertEquals("review", CacheConstants.REVIEW);
        assertEquals("review-list", CacheConstants.REVIEW_LIST);
        assertEquals("coupon", CacheConstants.COUPON);
        assertEquals("coupon-list", CacheConstants.COUPON_LIST);
        assertEquals("bestseller", CacheConstants.BESTSELLER);
        assertEquals("recommended", CacheConstants.RECOMMENDED);
    }

    @Test
    void keyPrefixes() {
        assertEquals("bhukkad:", CacheConstants.KEY_PREFIX);
        assertEquals(":", CacheConstants.KEY_SEPARATOR);
    }

    @Test
    void privateConstructor() throws Exception {
        Constructor<CacheConstants> ctor = CacheConstants.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        assertNotNull(ctor.newInstance());
    }
}
