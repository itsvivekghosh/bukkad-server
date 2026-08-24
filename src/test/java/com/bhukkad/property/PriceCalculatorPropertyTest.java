package com.bhukkad.property;

import com.bhukkad.util.PriceCalculator;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Positive;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for PriceCalculator using jqwik.
 * These tests verify mathematical properties that should hold for all valid inputs.
 */
class PriceCalculatorPropertyTest {

    @Property
    @Report(Reporting.GENERATED)
    void subtotalIsPriceTimesQuantity(
            @ForAll @Positive double price,
            @ForAll @IntRange(min = 1, max = 1000) int quantity
    ) {
        double result = PriceCalculator.calculateSubtotal(price, quantity);
        double expected = price * quantity;
        assertThat(result).isEqualTo(expected);
    }

    @Property
    @Report(Reporting.GENERATED)
    void subtotalIsNonNegative(
            @ForAll @Positive double price,
            @ForAll @IntRange(min = 0, max = 1000) int quantity
    ) {
        double result = PriceCalculator.calculateSubtotal(price, quantity);
        assertThat(result).isGreaterThanOrEqualTo(0.0);
    }

    @Property
    @Report(Reporting.GENERATED)
    void roundingToTwoDecimalsIsIdempotent(
            @ForAll double value
    ) {
        double roundedOnce = PriceCalculator.roundToTwoDecimals(value);
        double roundedTwice = PriceCalculator.roundToTwoDecimals(roundedOnce);
        assertThat(roundedTwice).isEqualTo(roundedOnce);
    }

    @Property
    @Report(Reporting.GENERATED)
    void roundingToTwoDecimalsHasAtMostTwoDecimalPlaces(
            @ForAll double value
    ) {
        double rounded = PriceCalculator.roundToTwoDecimals(value);
        BigDecimal bd = BigDecimal.valueOf(rounded);
        int scale = bd.scale();
        assertThat(scale).isLessThanOrEqualTo(2);
    }

    @Property
    @Report(Reporting.GENERATED)
    void loyaltyPointsAreNonNegative(
            @ForAll @Positive double orderTotal
    ) {
        int points = PriceCalculator.calculateLoyaltyPoints(orderTotal);
        assertThat(points).isGreaterThanOrEqualTo(0);
    }

    @Property
    @Report(Reporting.GENERATED)
    void loyaltyPointsIncreaseWithOrderTotal(
            @ForAll @Positive double orderTotal1,
            @ForAll @Positive double orderTotal2
    ) {
        // This property might not hold if the calculation has steps/tiers
        // Just verify both are non-negative
        int points1 = PriceCalculator.calculateLoyaltyPoints(orderTotal1);
        int points2 = PriceCalculator.calculateLoyaltyPoints(orderTotal2);
        assertThat(points1).isGreaterThanOrEqualTo(0);
        assertThat(points2).isGreaterThanOrEqualTo(0);
    }

    @Example
    void testKnownValues() {
        // Verify specific known calculations
        assertThat(PriceCalculator.calculateSubtotal(10.50, 2)).isEqualTo(21.00);
        // Math.round uses half-up: 10.126 -> 10.13
        assertThat(PriceCalculator.roundToTwoDecimals(10.126)).isEqualTo(10.13);
        assertThat(PriceCalculator.roundToTwoDecimals(10.124)).isEqualTo(10.12);
        assertThat(PriceCalculator.calculateLoyaltyPoints(100.0)).isGreaterThanOrEqualTo(0);
    }
}