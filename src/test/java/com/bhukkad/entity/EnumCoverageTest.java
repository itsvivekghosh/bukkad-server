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

    /**
     * V17 delivery proof. Both enums are persisted as {@code STRING}, so a rename here silently
     * breaks reads of existing {@code order_delivery_proofs} rows; pinning the names keeps the
     * entity and the V17 migration in agreement.
     */
    @Test
    void deliveryProofEnums_valuesAndValueOf() {
        assertEquals(4, OrderDeliveryProof.ProofType.values().length);
        assertEquals(OrderDeliveryProof.ProofType.OTP, OrderDeliveryProof.ProofType.valueOf("OTP"));
        assertEquals(OrderDeliveryProof.ProofType.PHOTO, OrderDeliveryProof.ProofType.valueOf("PHOTO"));
        assertEquals(OrderDeliveryProof.ProofType.OTP_AND_PHOTO,
                OrderDeliveryProof.ProofType.valueOf("OTP_AND_PHOTO"));
        assertEquals(OrderDeliveryProof.ProofType.SKIPPED,
                OrderDeliveryProof.ProofType.valueOf("SKIPPED"));

        assertEquals(4, OrderDeliveryProof.ProofStatus.values().length);
        assertEquals(OrderDeliveryProof.ProofStatus.PENDING,
                OrderDeliveryProof.ProofStatus.valueOf("PENDING"));
        assertEquals(OrderDeliveryProof.ProofStatus.VERIFIED,
                OrderDeliveryProof.ProofStatus.valueOf("VERIFIED"));
        assertEquals(OrderDeliveryProof.ProofStatus.FAILED,
                OrderDeliveryProof.ProofStatus.valueOf("FAILED"));
        assertEquals(OrderDeliveryProof.ProofStatus.SKIPPED,
                OrderDeliveryProof.ProofStatus.valueOf("SKIPPED"));
    }
}
