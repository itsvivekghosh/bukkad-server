package com.bhukkad.service;

import com.bhukkad.entity.Customer;
import com.bhukkad.entity.CartItem;
import com.bhukkad.entity.Coupon;
import com.bhukkad.entity.Restaurant;

import java.util.List;

public interface OrderPricingService {

    default OrderPricingResult calculate(
            Restaurant restaurant,
            List<CartItem> cartItems,
            String couponCode,
            Customer customer,
            Integer loyaltyPointsToRedeem,
            String paymentMethod,
            Double walletAmountToUse,
            Boolean useWallet) {
        return calculate(restaurant, cartItems, couponCode, customer, loyaltyPointsToRedeem,
                paymentMethod, walletAmountToUse, useWallet, null, null);
    }

    OrderPricingResult calculate(
            Restaurant restaurant,
            List<CartItem> cartItems,
            String couponCode,
            Customer customer,
            Integer loyaltyPointsToRedeem,
            String paymentMethod,
            Double walletAmountToUse,
            Boolean useWallet,
            Double deliveryLatitude,
            Double deliveryLongitude);

    void validateCartItems(Restaurant restaurant, List<CartItem> cartItems);

    record OrderPricingResult(
            double subtotal,
            double deliveryFee,
            double taxAmount,
            double discountAmount,
            double loyaltyDiscountAmount,
            int loyaltyPointsRedeemed,
            double walletAmountUsed,
            double totalAmount,
            double orderTotalBeforeWallet,
            Coupon appliedCoupon
    ) {}
}
