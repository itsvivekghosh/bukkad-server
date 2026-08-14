package com.bhukkad.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnumCoverageTest {

    @Test
    void restaurantFoodType_valuesAndValueOf() {
        assertEquals(4, Restaurant.FoodType.values().length);
        assertEquals(Restaurant.FoodType.VEG, Restaurant.FoodType.valueOf("VEG"));
        assertEquals(Restaurant.FoodType.NON_VEG, Restaurant.FoodType.valueOf("NON_VEG"));
        assertEquals(Restaurant.FoodType.VEGAN, Restaurant.FoodType.valueOf("VEGAN"));
        assertEquals(Restaurant.FoodType.GLUTEN_FREE, Restaurant.FoodType.valueOf("GLUTEN_FREE"));
    }

    @Test
    void otherEnums_areReachable() {
        assertTrue(User.UserRole.values().length > 0);
        assertTrue(MenuItem.FoodType.values().length > 0);
        assertTrue(MenuItem.SpiceLevel.values().length > 0);
        assertTrue(Order.OrderStatus.values().length > 0);
        assertTrue(Payment.PaymentMethod.values().length > 0);
        assertTrue(Payment.PaymentStatus.values().length > 0);
        assertTrue(Coupon.DiscountType.values().length > 0);
        assertTrue(Address.AddressType.values().length > 0);
    }
}
