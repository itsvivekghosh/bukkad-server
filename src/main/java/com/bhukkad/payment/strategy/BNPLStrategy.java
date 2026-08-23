package com.bhukkad.payment.strategy;

import com.bhukkad.entity.Order;
import com.bhukkad.entity.Payment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Buy-now-pay-later payment strategy.
 *
 * <p>Eligibility is validated in-memory: the order amount must exceed
 * {@link #MIN_ORDER_AMOUNT} and the customer's outstanding BNPL balance
 * (tracked in this singleton component) must not exceed
 * {@link #MAX_PENDING_BALANCE}. On approval the amount is added to the
 * customer's pending balance and the payment is marked completed.
 */
@Slf4j
@Component
public class BNPLStrategy implements PaymentStrategy {

    public static final double MIN_ORDER_AMOUNT = 1000.0;
    public static final double MAX_PENDING_BALANCE = 5000.0;

    private final ConcurrentHashMap<Long, Double> pendingBalances = new ConcurrentHashMap<>();

    @Override
    public Payment process(PaymentContext context) {
        Payment payment = context.payment();
        Order order = context.order();
        Long customerId = order.getCustomer() != null ? order.getCustomer().getId() : null;
        double amount = order.getTotalAmount() != null ? order.getTotalAmount() : context.gatewayAmount();

        if (customerId == null || amount <= 0 || !isEligible(customerId, amount)) {
            payment.setStatus(Payment.PaymentStatus.FAILED);
            payment.setPaymentGatewayResponse("BNPL_INELIGIBLE");
            log.warn("BNPL payment rejected | customerId={} | orderId={} | amount={}",
                    customerId, order.getId(), amount);
            return payment;
        }

        pendingBalances.merge(customerId, amount, Double::sum);
        payment.setTransactionId("BNPL-" + order.getOrderNumber());
        payment.setPaymentGatewayResponse("BNPL_APPROVED");
        payment.setStatus(Payment.PaymentStatus.COMPLETED);
        payment.setCompletedAt(LocalDateTime.now());
        log.info("BNPL payment approved | customerId={} | orderId={} | amount={} | pending={}",
                customerId, order.getId(), amount, pendingBalances.get(customerId));
        return payment;
    }

    public boolean isEligible(Long customerId, double amount) {
        if (customerId == null || amount <= MIN_ORDER_AMOUNT) {
            return false;
        }
        return pendingBalance(customerId) + amount <= MAX_PENDING_BALANCE;
    }

    public boolean isEligibleForNewOrder(Long customerId) {
        return customerId != null && pendingBalance(customerId) < MAX_PENDING_BALANCE;
    }

    public double pendingBalance(Long customerId) {
        if (customerId == null) {
            return 0.0;
        }
        return pendingBalances.getOrDefault(customerId, 0.0);
    }
}
