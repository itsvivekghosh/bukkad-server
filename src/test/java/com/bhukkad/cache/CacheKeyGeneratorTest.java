package com.bhukkad.cache;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.*;

class CacheKeyGeneratorTest {

    @Test
    void restaurantKeys() {
        assertEquals("restaurant:5", CacheKeyGenerator.restaurant(5L));
        assertEquals("restaurant-list:all", CacheKeyGenerator.restaurantList());
        assertEquals("restaurant-list:owner:9", CacheKeyGenerator.restaurantsByOwner(9L));
        assertEquals("restaurant-search:pizza", CacheKeyGenerator.restaurantSearch("  Pizza "));
        assertEquals("restaurant-filter:3:true", CacheKeyGenerator.restaurantFilter(3L, true));
        assertEquals("restaurant-filter:null:null", CacheKeyGenerator.restaurantFilter(null, null));
        assertEquals("restaurant-filter:1:false", CacheKeyGenerator.restaurantFilter(1L, false));
    }

    @Test
    void restaurantSearch_nullKeywordThrowsNpe() {
        assertThrows(NullPointerException.class, () -> CacheKeyGenerator.restaurantSearch(null));
    }

    @Test
    void menuKeys() {
        assertEquals("menu-item:11", CacheKeyGenerator.menuItem(11L));
        assertEquals("menu-item-list:restaurant:4", CacheKeyGenerator.menuItemsByRestaurant(4L));
        assertEquals("menu-item-list:category:8", CacheKeyGenerator.menuItemsByCategory(8L));
        assertEquals("bestseller:4", CacheKeyGenerator.bestsellers(4L));
        assertEquals("recommended:4", CacheKeyGenerator.recommended(4L));
        assertEquals("menu-category:2", CacheKeyGenerator.menuCategory(2L));
        assertEquals("menu-category-list:restaurant:4", CacheKeyGenerator.menuCategoriesByRestaurant(4L));
    }

    @Test
    void cuisineUserCartOrderReviewCouponKeys() {
        assertEquals("cuisine:1", CacheKeyGenerator.cuisine(1L));
        assertEquals("cuisine-list:all", CacheKeyGenerator.cuisineList());
        assertEquals("user-profile:7", CacheKeyGenerator.userProfile(7L));
        assertEquals("cart:7", CacheKeyGenerator.cart(7L));
        assertEquals("order:15", CacheKeyGenerator.order(15L));
        assertEquals("order-track:15:customer:7", CacheKeyGenerator.orderTrack(15L, 7L));
        assertEquals("kitchen-queue:4", CacheKeyGenerator.kitchenQueue(4L));
        assertEquals("order-list:customer:7", CacheKeyGenerator.customerOrders(7L));
        assertEquals("order-list:restaurant:4", CacheKeyGenerator.restaurantOrders(4L));
        assertEquals("review-list:restaurant:4", CacheKeyGenerator.restaurantReviews(4L));
        assertEquals("coupon:SAVE10", CacheKeyGenerator.coupon("SAVE10"));
        assertEquals("coupon-list:restaurant:4", CacheKeyGenerator.activeCoupons(4L));
    }

    @Test
    void homeFeedAndServiceabilityKeys() {
        assertEquals("home-feed:banners", CacheKeyGenerator.homeFeedBanners());
        assertEquals("home-feed:campaigns", CacheKeyGenerator.homeFeedCampaigns());
        assertEquals("home-feed:membership-plans", CacheKeyGenerator.homeFeedMembershipPlans());
        assertEquals("serviceability:restaurant:4:12.9:77.6:250.0",
                CacheKeyGenerator.serviceability(4L, 12.9, 77.6, 250.0));
        assertEquals("serviceability:restaurant:4:0.0:0.0:0.0",
                CacheKeyGenerator.serviceability(4L, 0.0, 0.0, 0.0));
    }

    @Test
    void invalidationPatterns() {
        assertEquals("restaurant", CacheKeyGenerator.restaurantPattern());
        assertEquals("menu-item:*restaurant:4", CacheKeyGenerator.menuItemPattern(4L));
        assertEquals("order-list:customer:7", CacheKeyGenerator.orderPattern(7L));
        assertEquals("order-track:15:", CacheKeyGenerator.orderTrackPattern(15L));
    }

    @Test
    void privateConstructor() throws Exception {
        Constructor<CacheKeyGenerator> ctor = CacheKeyGenerator.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        assertNotNull(ctor.newInstance());
    }
}
