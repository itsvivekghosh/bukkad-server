package com.bhukkad.serviceImpl;

import com.bhukkad.entity.CartItem;
import com.bhukkad.entity.Coupon;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.MenuItem;
import com.bhukkad.entity.Payment;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.service.CouponService;
import com.bhukkad.service.OrderPricingService;
import com.bhukkad.util.Constants;
import com.bhukkad.util.PriceCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderPricingServiceImpl implements OrderPricingService {

    private final CouponService couponService;

    @Override
    public void validateCartItems(Restaurant restaurant, List<CartItem> cartItems) {
        validateCartItemsForRestaurant(cartItems, restaurant.getId());
    }

    @Override
    public OrderPricingResult calculate(
            Restaurant restaurant,
            List<CartItem> cartItems,
            String couponCode,
            Customer customer,
            Integer loyaltyPointsToRedeem,
            String paymentMethod,
            Double walletAmountToUse,
            Boolean useWallet) {
        if (cartItems == null || cartItems.isEmpty()) {
            throw new BusinessException("Cart is empty");
        }

        double subtotal = cartItems.stream()
                .mapToDouble(item -> PriceCalculator.calculateSubtotal(item.getMenuItem().getPrice(), item.getQuantity()))
                .sum();
        subtotal = PriceCalculator.roundToTwoDecimals(subtotal);

        if (restaurant.getMinimumOrderAmount() != null && subtotal < restaurant.getMinimumOrderAmount()) {
            throw new BusinessException("Minimum order amount is ₹" + restaurant.getMinimumOrderAmount());
        }

        double deliveryFee = resolveDeliveryFee(restaurant, subtotal);
        double taxAmount = PriceCalculator.roundToTwoDecimals(PriceCalculator.calculateTax(subtotal));

        Coupon appliedCoupon = null;
        double discountAmount = 0.0;
        if (StringUtils.hasText(couponCode)) {
            appliedCoupon = couponService.validateCoupon(couponCode, subtotal, restaurant.getId());
            discountAmount = PriceCalculator.roundToTwoDecimals(
                    couponService.calculateDiscount(appliedCoupon, subtotal));
        }

        int pointsToRedeem = loyaltyPointsToRedeem != null ? Math.max(0, loyaltyPointsToRedeem) : 0;
        if (pointsToRedeem > 0) {
            if (customer.getLoyaltyPoints() < pointsToRedeem) {
                throw new BusinessException("Insufficient loyalty points");
            }
        }
        double loyaltyDiscountAmount = PriceCalculator.roundToTwoDecimals(
                PriceCalculator.convertPointsToRupees(pointsToRedeem));

        double orderTotalBeforeWallet = PriceCalculator.roundToTwoDecimals(
                PriceCalculator.calculateTotal(subtotal, deliveryFee, taxAmount, discountAmount + loyaltyDiscountAmount));

        double walletAmountUsed = resolveWalletAmountUsed(
                customer, paymentMethod, walletAmountToUse, useWallet, orderTotalBeforeWallet);

        double totalAmount = PriceCalculator.roundToTwoDecimals(orderTotalBeforeWallet - walletAmountUsed);
        if (totalAmount < 0) {
            throw new BusinessException("Invalid order total");
        }

        return new OrderPricingResult(
                subtotal,
                deliveryFee,
                taxAmount,
                discountAmount,
                loyaltyDiscountAmount,
                pointsToRedeem,
                walletAmountUsed,
                totalAmount,
                orderTotalBeforeWallet,
                appliedCoupon);
    }

    private double resolveWalletAmountUsed(
            Customer customer,
            String paymentMethod,
            Double walletAmountToUse,
            Boolean useWallet,
            double orderTotalBeforeWallet) {
        if (isWalletPayment(paymentMethod)) {
            if (customer.getWalletBalance() < orderTotalBeforeWallet) {
                throw new BusinessException("Insufficient wallet balance");
            }
            return orderTotalBeforeWallet;
        }

        double requested = 0.0;
        if (walletAmountToUse != null && walletAmountToUse > 0) {
            requested = walletAmountToUse;
        } else if (Boolean.TRUE.equals(useWallet)) {
            requested = Math.min(customer.getWalletBalance(), orderTotalBeforeWallet);
        }

        if (requested <= 0) {
            return 0.0;
        }
        if (requested > customer.getWalletBalance()) {
            throw new BusinessException("Insufficient wallet balance");
        }
        if (requested > orderTotalBeforeWallet) {
            throw new BusinessException("Wallet amount cannot exceed order total");
        }
        return PriceCalculator.roundToTwoDecimals(requested);
    }

    private boolean isWalletPayment(String paymentMethod) {
        if (!StringUtils.hasText(paymentMethod)) {
            return false;
        }
        String normalized = paymentMethod.trim().toUpperCase();
        return Payment.PaymentMethod.WALLET.name().equals(normalized);
    }

    private double resolveDeliveryFee(Restaurant restaurant, double subtotal) {
        if (Boolean.TRUE.equals(restaurant.getFreeDeliveryAvailable())) {
            Double threshold = restaurant.getFreeDeliveryAbove();
            if (threshold == null || subtotal >= threshold) {
                return 0.0;
            }
        }
        Double fee = restaurant.getDeliveryFee();
        return fee != null ? fee : Constants.DEFAULT_DELIVERY_FEE;
    }

    static void validateCartItemsForRestaurant(List<CartItem> cartItems, Long restaurantId) {
        for (CartItem cartItem : cartItems) {
            MenuItem menuItem = cartItem.getMenuItem();
            if (!Boolean.TRUE.equals(menuItem.getAvailable())) {
                throw new BusinessException("Menu item is not available: " + menuItem.getName());
            }
            Long itemRestaurantId = menuItem.getCategory().getRestaurant().getId();
            if (!itemRestaurantId.equals(restaurantId)) {
                throw new BusinessException("Cart contains items from a different restaurant");
            }
        }
    }
}
