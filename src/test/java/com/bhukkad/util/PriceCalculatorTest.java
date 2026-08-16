package com.bhukkad.util;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PriceCalculatorTest {

    @Test
    void calculations_coverAllFormulas() {
        assertEquals(250.0, PriceCalculator.calculateSubtotal(125.0, 2));
        assertEquals(12.5, PriceCalculator.calculateTax(250.0));
        assertEquals(25.0, PriceCalculator.calculateDiscount(250.0, 10));
        assertEquals(277.5, PriceCalculator.calculateTotal(250.0, 40.0, 12.5, 25.0));
        assertEquals(3, PriceCalculator.calculateLoyaltyPoints(399.0));
        assertEquals(5.0, PriceCalculator.convertPointsToRupees(50));
        assertEquals(12.35, PriceCalculator.roundToTwoDecimals(12.345));
        assertEquals(12.35, PriceCalculator.roundToTwoDecimals(12.346));
    }

    @Test
    void constructor_isPrivate() throws Exception {
        Constructor<PriceCalculator> constructor = PriceCalculator.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        constructor.newInstance();
    }
}
