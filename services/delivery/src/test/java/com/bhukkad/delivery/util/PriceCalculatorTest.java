package com.bhukkad.delivery.util;

import com.bhukkad.common.util.Constants;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Money math: subtotal/tax/discount composition, loyalty accrual bands and
 * two-decimal rounding used at the billing boundary.
 */
class PriceCalculatorTest {

    @Test
    void subtotalAndTaxAndDiscount() {
        assertThat(PriceCalculator.calculateSubtotal(120.0, 2)).isEqualTo(240.0);
        assertThat(PriceCalculator.calculateTax(200.0)).isCloseTo(200.0 * Constants.TAX_RATE, within(0.0001));
        assertThat(PriceCalculator.calculateDiscount(200.0, 10)).isEqualTo(20.0);
        assertThat(PriceCalculator.calculateDiscount(200.0, 0)).isZero();
    }

    @Test
    void total_composesBillLines() {
        double total = PriceCalculator.calculateTotal(240.0, 40.0, 14.0, 20.0);

        assertThat(total).isCloseTo(274.0, within(0.0001));
    }

    @Test
    void loyaltyPoints_accruePerFullHundred() {
        assertThat(PriceCalculator.calculateLoyaltyPoints(250.0))
                .isEqualTo(2 * Constants.POINTS_PER_HUNDRED);
        assertThat(PriceCalculator.calculateLoyaltyPoints(99.99)).isZero();
        assertThat(PriceCalculator.calculateLoyaltyPoints(0)).isZero();
    }

    @Test
    void pointsConvertToRupeesAtFixedRatio() {
        assertThat(PriceCalculator.convertPointsToRupees(Constants.POINTS_TO_RUPEE_RATIO))
                .isEqualTo(1.0);
        assertThat(PriceCalculator.convertPointsToRupees(55)).isCloseTo(5.5, within(0.0001));
        assertThat(PriceCalculator.convertPointsToRupees(0)).isZero();
    }

    @Test
    void roundToTwoDecimals() {
        assertThat(PriceCalculator.roundToTwoDecimals(10.0 / 3)).isEqualTo(3.33);
        assertThat(PriceCalculator.roundToTwoDecimals(2.456)).isEqualTo(2.46);
        assertThat(PriceCalculator.roundToTwoDecimals(7.0)).isEqualTo(7.0);
    }
}
