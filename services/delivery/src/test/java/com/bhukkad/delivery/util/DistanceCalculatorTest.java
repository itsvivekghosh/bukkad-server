package com.bhukkad.delivery.util;

import com.bhukkad.common.util.Constants;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Legacy geodesic helper: golden km values, ETA/fee distance bands and the
 * conservative bounding-box pre-filter deltas.
 */
class DistanceCalculatorTest {

    @Test
    void calculateDistance_symmetricHaversine() {
        double a = DistanceCalculator.calculateDistance(12.9716, 77.5946, 12.9698, 77.7500);
        double b = DistanceCalculator.calculateDistance(12.9698, 77.7500, 12.9716, 77.5946);

        assertThat(a).isCloseTo(b, within(0.0001));
        assertThat(a).isBetween(15.0, 17.0);
    }

    @Test
    void calculateDistance_samePointIsZero() {
        assertThat(DistanceCalculator.calculateDistance(19.076, 72.877, 19.076, 72.877)).isZero();
    }

    @Test
    void calculateDistance_halfEarth() {
        double d = DistanceCalculator.calculateDistance(90, 0, -90, 0);
        assertThat(d).isCloseTo(Math.PI * 6371, within(10.0));
    }

    @Test
    void calculateDeliveryTime_addsPrepOnTopOfTravel() {
        assertThat(DistanceCalculator.calculateDeliveryTime(0)).isEqualTo(10);
        assertThat(DistanceCalculator.calculateDeliveryTime(5)).isEqualTo(25);
        // 3.33 km -> 9.99 min of travel -> ceiling to whole minutes + 10 prep
        assertThat(DistanceCalculator.calculateDeliveryTime(3.33)).isEqualTo(20);
    }

    @Test
    void calculateDeliveryFee_tieredByDistance() {
        assertThat(DistanceCalculator.calculateDeliveryFee(1.5)).isEqualTo(20.0);
        assertThat(DistanceCalculator.calculateDeliveryFee(2.0)).isEqualTo(20.0);
        assertThat(DistanceCalculator.calculateDeliveryFee(2.1)).isEqualTo(40.0);
        assertThat(DistanceCalculator.calculateDeliveryFee(5.0)).isEqualTo(40.0);
        assertThat(DistanceCalculator.calculateDeliveryFee(5.5)).isEqualTo(60.0);
        assertThat(DistanceCalculator.calculateDeliveryFee(8.0)).isEqualTo(60.0);
        assertThat(DistanceCalculator.calculateDeliveryFee(8.5)).isEqualTo(80.0);
    }

    @Test
    void isDeliveryPossible_respectsMaxRadius() {
        assertThat(DistanceCalculator.isDeliveryPossible(3.0)).isTrue();
        assertThat(DistanceCalculator.isDeliveryPossible(Constants.MAX_DELIVERY_DISTANCE_KM)).isTrue();
        assertThat(DistanceCalculator.isDeliveryPossible(Constants.MAX_DELIVERY_DISTANCE_KM + 0.1)).isFalse();
    }

    @Test
    void boundingBoxDeltas_useEquatorWorstCase() {
        double[] deltas = DistanceCalculator.boundingBoxDeltas(111.32);

        assertThat(deltas).hasSize(2);
        assertThat(deltas[0]).isCloseTo(1.0, within(0.0001));
        assertThat(deltas[1]).isCloseTo(1.0, within(0.0001));
        assertThat(DistanceCalculator.boundingBoxDeltas(0)).containsExactly(0.0, 0.0);
    }
}
