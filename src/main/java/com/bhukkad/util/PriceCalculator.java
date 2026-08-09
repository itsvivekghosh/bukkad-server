package com.bhukkad.util;

public class PriceCalculator {

    /**
     * Calculate subtotal from item price and quantity
     */
    public static double calculateSubtotal(double price, int quantity) {
        return price * quantity;
    }

    /**
     * Calculate tax amount
     */
    public static double calculateTax(double amount) {
        return amount * Constants.TAX_RATE;
    }

    /**
     * Calculate discount amount
     */
    public static double calculateDiscount(double amount, double discountPercentage) {
        return amount * (discountPercentage / 100);
    }

    /**
     * Calculate final total
     */
    public static double calculateTotal(double subtotal, double deliveryFee,
                                        double tax, double discount) {
        return subtotal + deliveryFee + tax - discount;
    }

    /**
     * Calculate loyalty points from amount
     */
    public static int calculateLoyaltyPoints(double amount) {
        return (int) (amount / 100) * Constants.POINTS_PER_HUNDRED;
    }

    /**
     * Convert loyalty points to rupees
     */
    public static double convertPointsToRupees(int points) {
        return points / (double) Constants.POINTS_TO_RUPEE_RATIO;
    }

    /**
     * Round to 2 decimal places
     */
    public static double roundToTwoDecimals(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private PriceCalculator() {
        // Private constructor to prevent instantiation
    }
}