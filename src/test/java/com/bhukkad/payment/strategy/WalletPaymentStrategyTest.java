package com.bhukkad.payment.strategy;

import com.bhukkad.entity.Order;
import com.bhukkad.entity.Payment;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WalletPaymentStrategyTest {

    @Test
    void testProcess_Wallet_MarksAsCompleted() {
        WalletPaymentStrategy strategy = new WalletPaymentStrategy();

        Order order = new Order();
        order.setOrderNumber("ORD-12345678");

        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setPaymentMethod(Payment.PaymentMethod.WALLET);

        PaymentContext context = new PaymentContext(order, payment, "idem-key", 0.0);

        Payment result = strategy.process(context);

        assertEquals(Payment.PaymentStatus.COMPLETED, result.getStatus());
        assertEquals("WALLET-ORD-12345678", result.getTransactionId());
        assertNotNull(result.getCompletedAt());
    }

    @Test
    void testProcess_Wallet_SetsCorrectTransactionId() {
        WalletPaymentStrategy strategy = new WalletPaymentStrategy();

        Order order = new Order();
        order.setOrderNumber("ORD-ABCDEFGH");

        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setPaymentMethod(Payment.PaymentMethod.WALLET);

        PaymentContext context = new PaymentContext(order, payment, null, 0.0);

        Payment result = strategy.process(context);

        assertEquals("WALLET-ORD-ABCDEFGH", result.getTransactionId());
    }

    @Test
    void testProcess_Wallet_WithIdempotencyKey() {
        WalletPaymentStrategy strategy = new WalletPaymentStrategy();

        Order order = new Order();
        order.setOrderNumber("ORD-TEST1234");

        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setPaymentMethod(Payment.PaymentMethod.WALLET);

        PaymentContext context = new PaymentContext(order, payment, "idem-key", 0.0);

        Payment result = strategy.process(context);

        assertEquals(Payment.PaymentStatus.COMPLETED, result.getStatus());
        assertNotNull(result.getCompletedAt());
        assertEquals("WALLET-ORD-TEST1234", result.getTransactionId());
    }
}
