package com.bhukkad.delivery.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Deterministic Haversine formula: dependency-free, no mocking needed.
 */
@ExtendWith(MockitoExtension.class)
class HaversineDistanceCalculatorTest {

    private final HaversineDistanceCalculator calc = new HaversineDistanceCalculator();

    @Test
    void zeroDistance() {
        assertThat(calc.distanceKm(12.97, 77.59, 12.97, 77.59)).isCloseTo(0.0, within(0.001));
    }

    @Test
    void knownDistance() {
        // Bangalore MG Road (12.9716, 77.5946) → Whitefield (12.9698, 77.7500)
        // Approx 15.5 km. Actual: ~15.7 km.
        double d = calc.distanceKm(12.9716, 77.5946, 12.9698, 77.7500);
        assertThat(d).isGreaterThan(15.0).isLessThan(17.0);
    }

    @Test
    void symmetric() {
        double a = calc.distanceKm(12.97, 77.59, 13.04, 80.28);
        double b = calc.distanceKm(13.04, 80.28, 12.97, 77.59);
        assertThat(a).isCloseTo(b, within(0.0001));
    }

    @Test
    void antipodalPoints() {
        // North pole to South pole — great circle
        double d = calc.distanceKm(90, 0, -90, 0);
        assertThat(d).isCloseTo(Math.PI * 6371, within(10.0));
    }

    @Test
    void withinTheSameCity() {
        // Indiranagar → Koramangala, Bangalore ~7 km
        double d = calc.distanceKm(12.978, 77.640, 12.935, 77.624);
        assertThat(d).isGreaterThan(2.0).isLessThan(12.0);
    }
}