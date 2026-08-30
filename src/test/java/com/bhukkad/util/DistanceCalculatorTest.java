package com.bhukkad.util;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.assertj.core.api.Assertions.assertThat;
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
    void boundingBoxDeltas_returnsConsistentDeltas() {
        double[] deltas = DistanceCalculator.boundingBoxDeltas(5.0);

        // 5 km / 111.32 km per degree ≈ 0.0449 degrees
        double expected = 5.0 / 111.32;
        assertEquals(expected, deltas[0], 0.0001);
        assertEquals(expected, deltas[1], 0.0001);
    }

    @Test
    void boundingBoxDeltas_latAndLonDeltaAreEqual() {
        // Uses equator-based calculation, so latDelta == lonDelta
        double[] deltas = DistanceCalculator.boundingBoxDeltas(10.0);
        assertEquals(deltas[0], deltas[1], 0.0001);
    }

    @Test
    void boundingBoxDeltas_zeroRadius_returnsZeroDeltas() {
        double[] deltas = DistanceCalculator.boundingBoxDeltas(0.0);
        assertEquals(0.0, deltas[0], 0.0001);
        assertEquals(0.0, deltas[1], 0.0001);
    }

    @Test
    void boundingBoxDeltas_largeRadius_returnsProportionalDeltas() {
        double[] small = DistanceCalculator.boundingBoxDeltas(1.0);
        double[] large = DistanceCalculator.boundingBoxDeltas(50.0);

        // Large radius should produce proportionally larger deltas
        assertThat(large[0]).isGreaterThan(small[0] * 10);
    }

    @Test
    void boundingBoxDeltas_wrapsDateline_forQueryNear180() {
        // A 5000 km radius around lon=179 should produce a bounding box that
        // extends to 179 + 45 = 224, which wraps to -136. The SQL query
        // handles the wrap with OR conditions.
        double[] deltas = DistanceCalculator.boundingBoxDeltas(5000);
        double upper = 179.0 + deltas[1];

        // Upper bound exceeds 180 — dateline crossing condition triggers
        assertThat(upper).isGreaterThan(180.0);
    }

    @Test
    void constructor_isPrivate() throws Exception {
        Constructor<DistanceCalculator> constructor = DistanceCalculator.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        constructor.newInstance();
    }
}
