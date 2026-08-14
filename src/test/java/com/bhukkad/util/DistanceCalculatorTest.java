package com.bhukkad.util;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DistanceCalculatorTest {

    @Test
    void calculateDistance_samePoint_isZero() {
        assertEquals(0.0, DistanceCalculator.calculateDistance(12.97, 77.59, 12.97, 77.59), 0.0001);
    }

    @Test
    void calculateDistance_knownCities_isPositive() {
        double distance = DistanceCalculator.calculateDistance(12.9716, 77.5946, 13.0827, 80.2707);
        assertTrue(distance > 250);
        assertTrue(distance < 400);
    }

    @Test
    void calculateDeliveryTime_addsPreparationBuffer() {
        assertEquals(10, DistanceCalculator.calculateDeliveryTime(0));
        assertEquals(13, DistanceCalculator.calculateDeliveryTime(1));
        assertEquals(40, DistanceCalculator.calculateDeliveryTime(10));
    }

    @Test
    void calculateDeliveryFee_usesDistanceTiers() {
        assertEquals(20.0, DistanceCalculator.calculateDeliveryFee(0));
        assertEquals(20.0, DistanceCalculator.calculateDeliveryFee(2));
        assertEquals(40.0, DistanceCalculator.calculateDeliveryFee(2.1));
        assertEquals(40.0, DistanceCalculator.calculateDeliveryFee(5));
        assertEquals(60.0, DistanceCalculator.calculateDeliveryFee(5.1));
        assertEquals(60.0, DistanceCalculator.calculateDeliveryFee(8));
        assertEquals(80.0, DistanceCalculator.calculateDeliveryFee(8.1));
    }

    @Test
    void isDeliveryPossible_respectsMaxDistance() {
        assertTrue(DistanceCalculator.isDeliveryPossible(10.0));
        assertFalse(DistanceCalculator.isDeliveryPossible(10.1));
    }

    @Test
    void constructor_isPrivate() throws Exception {
        Constructor<DistanceCalculator> constructor = DistanceCalculator.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        constructor.newInstance();
    }
}
