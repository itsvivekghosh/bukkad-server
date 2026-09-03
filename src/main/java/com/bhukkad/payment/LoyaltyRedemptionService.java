package com.bhukkad.payment;

import com.bhukkad.config.LoyaltyProperties;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.Order;
import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.common.error.UnauthorizedException;
import com.bhukkad.repository.CustomerRepository;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.service.OrderPricingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies a loyalty-point discount to an order before payment. Converts points
 * to rupees at {@code app.loyalty.points-per-rupee}, caps the redemption at
 * {@code app.loyalty.max-redemption-percent} of the order total, applies the
 * discount through {@link OrderPricingService#applyLoyaltyDiscount} and
 * debits the customer's point balance.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoyaltyRedemptionService {

    private final LoyaltyProperties loyaltyProperties;
    private final OrderRepository orderRepository;
    private final CustomerRepository customerRepository;
    private final OrderPricingService orderPricingService;

    @Transactional
    public LoyaltyRedemptionResult redeemPoints(Long customerId, Long orderId, int points) {
        if (!loyaltyProperties.isEnabled()) {
            throw new BusinessException("Loyalty redemption is disabled");
        }
        if (points <= 0) {
            throw new BusinessException("Points to redeem must be positive");
        }
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        if (order.getCustomer() == null || !order.getCustomer().getId().equals(customerId)) {
            throw new UnauthorizedException("You can only redeem loyalty points for your own orders");
        }

        double orderTotal = order.getTotalAmount() != null ? order.getTotalAmount() : 0.0;
        if (orderTotal < loyaltyProperties.getMinOrderAmount()) {
            throw new BusinessException("Minimum order amount for loyalty redemption is ₹"
                    + loyaltyProperties.getMinOrderAmount());
        }
        int pointsPerRupee = loyaltyProperties.getPointsPerRupee();
        if (points < pointsPerRupee) {
            throw new BusinessException("Minimum points to redeem is " + pointsPerRupee);
        }
        if (order.getLoyaltyPointsRedeemed() != null && order.getLoyaltyPointsRedeemed() > 0) {
            throw new BusinessException("Loyalty points already redeemed for this order");
        }

        int maxDiscountPoints = (int) Math.floor(
                orderTotal * loyaltyProperties.getMaxRedemptionPercent() / 100.0 * pointsPerRupee);
        int redeemedPoints = Math.min(points, maxDiscountPoints);
        if (redeemedPoints <= 0) {
            throw new BusinessException("Loyalty discount for this order would be zero");
        }

        Customer customer = order.getCustomer();
        if (customer.getLoyaltyPoints() == null || customer.getLoyaltyPoints() < redeemedPoints) {
            throw new BusinessException("Insufficient loyalty points");
        }

        double discountAmount = orderPricingService.applyLoyaltyDiscount(orderId, customerId, redeemedPoints);
        customer.setLoyaltyPoints(customer.getLoyaltyPoints() - redeemedPoints);
        customerRepository.save(customer);

        log.info("Loyalty points redeemed | customerId={} | orderId={} | points={} | discount={}",
                customerId, orderId, redeemedPoints, discountAmount);
        return new LoyaltyRedemptionResult(
                redeemedPoints,
                discountAmount,
                order.getTotalAmount() != null ? order.getTotalAmount() : 0.0);
    }

    public record LoyaltyRedemptionResult(int pointsRedeemed, double discountAmount, double orderTotal) {
    }
}
