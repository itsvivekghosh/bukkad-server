package com.bhukkad.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Haversine distance, ETA/fee tiers, delivery radius bound and index-friendly
 * bounding-box deltas.
 */
class DistanceCalculatorTest {

    // Bandra → Andheri (Mumbai), roughly 13 km apart.
    private static final double LAT1 = 19.0596, LON1 = 72.8295;
    private static final double LAT2 = 19.1197, LON2 = 72.8465;

    @Test
    void haversine_matchesPublishedDistanceWithinTolerance() {
        double km = DistanceCalculator.calculateDistance(12.9716, 77.5946, 19.0760, 72.8777);
        // Bangalore → Mumbai great-circle
        // BLR → BOM great-circle ≈ 844 km
        assertThat(km).isBetween(830.0, 860.0);
    }

    @Test
    void identicalPoints_haveZeroDistance() {
        assertThat(DistanceCalculator.calculateDistance(LAT1, LON1, LAT1, LON1)).isZero();
    }

    @Test
    void deliveryTimeIsCappedMinutesPlusPrepAllowance() {
        assertThat(DistanceCalculator.calculateDeliveryTime(0)).isEqualTo(10);
        assertThat(DistanceCalculator.calculateDeliveryTime(1)).isEqualTo(13);   // 3 min + 10
        assertThat(DistanceCalculator.calculateDeliveryTime(10)).isEqualTo(40);  // 30 min + 10
    }

    @Test
    void feeTiers_followDistanceBands() {
        assertThat(DistanceCalculator.calculateDeliveryFee(0.5)).isEqualTo(20.0);
        assertThat(DistanceCalculator.calculateDeliveryFee(2)).isEqualTo(20.0);
        assertThat(DistanceCalculator.calculateDeliveryFee(2.1)).isEqualTo(40.0);
        assertThat(DistanceCalculator.calculateDeliveryFee(5)).isEqualTo(40.0);
        assertThat(DistanceCalculator.calculateDeliveryFee(5.5)).isEqualTo(60.0);
        assertThat(DistanceCalculator.calculateDeliveryFee(8)).isEqualTo(60.0);
        assertThat(DistanceCalculator.calculateDeliveryFee(8.5)).isEqualTo(80.0);
        assertThat(DistanceCalculator.calculateDeliveryFee(99)).isEqualTo(80.0);
    }

    @Test
    void feasibility_followsMaxDeliveryDistance() {
        double max = Constants.MAX_DELIVERY_DISTANCE_KM;
        assertThat(DistanceCalculator.isDeliveryPossible(max)).isTrue();
        assertThat(DistanceCalculator.isDeliveryPossible(max + 0.001)).isFalse();
    }

    @Test
    void boundingBoxDeltas_areRadiusScaled() {
        double[] deltas = DistanceCalculator.boundingBoxDeltas(5.0);
        assertThat(deltas).hasSize(2);
        assertThat(deltas[0]).isEqualTo(5.0 / 111.32);
        assertThat(deltas[1]).isEqualTo(5.0 / 111.32);
    }
}
