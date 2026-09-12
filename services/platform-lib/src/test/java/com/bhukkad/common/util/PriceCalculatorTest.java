package com.bhukkad.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Money math helpers: subtotal/tax/discount composition, loyalty rounding and
 * two-decimal currency rounding.
 */
class PriceCalculatorTest {

    @Test
    void subtotal_multipliesPriceByQuantity() {
        assertThat(PriceCalculator.calculateSubtotal(120.5, 3)).isEqualTo(361.5);
        assertThat(PriceCalculator.calculateSubtotal(10, 0)).isZero();
    }

    @Test
    void tax_usesPlatformTaxRate() {
        assertThat(PriceCalculator.calculateTax(100)).isEqualTo(100 * Constants.TAX_RATE);
    }

    @Test
    void discount_isPercentageBased() {
        assertThat(PriceCalculator.calculateDiscount(200, 10)).isEqualTo(20.0);
        assertThat(PriceCalculator.calculateDiscount(200, 0)).isZero();
    }

    @Test
    void total_composesDeliveryAndDiscounts() {
        assertThat(PriceCalculator.calculateTotal(300, 40, 30, 50)).isEqualTo(320.0);
    }

    @Test
    void loyaltyPoints_floorPerHundred() {
        assertThat(PriceCalculator.calculateLoyaltyPoints(250))
                .isEqualTo(2 * Constants.POINTS_PER_HUNDRED);
        assertThat(PriceCalculator.calculateLoyaltyPoints(99)).isZero();
    }

    @Test
    void pointsConvertFractionallyToRupees() {
        assertThat(PriceCalculator.convertPointsToRupees(Constants.POINTS_TO_RUPEE_RATIO))
                .isEqualTo(1.0);
        assertThat(PriceCalculator.convertPointsToRupees(0)).isZero();
    }

    @Test
    void rounding_keepsTwoDecimals() {
        assertThat(PriceCalculator.roundToTwoDecimals(123.456)).isEqualTo(123.46);
        assertThat(PriceCalculator.roundToTwoDecimals(123.454)).isEqualTo(123.45);
        assertThat(PriceCalculator.roundToTwoDecimals(0.001)).isZero();
    }
}
