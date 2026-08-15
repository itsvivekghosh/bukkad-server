package com.bhukkad.payment.strategy;

import com.bhukkad.entity.Order;
import com.bhukkad.entity.Payment;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CODPaymentStrategyTest {

    @Test
    void testProcess_COD_MarksAsPending() {
        CODPaymentStrategy strategy = new CODPaymentStrategy();

        Order order = new Order();
        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setPaymentMethod(Payment.PaymentMethod.CASH_ON_DELIVERY);
        payment.setStatus(Payment.PaymentStatus.PENDING);

        PaymentContext context = new PaymentContext(order, payment, null, 0.0);

        Payment result = strategy.process(context);

        assertEquals(Payment.PaymentStatus.PENDING, result.getStatus());
        assertEquals(payment, result);
    }

    @Test
    void testProcess_ReturnsSamePayment() {
        CODPaymentStrategy strategy = new CODPaymentStrategy();

        Order order = new Order();
        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setPaymentMethod(Payment.PaymentMethod.CASH_ON_DELIVERY);
        payment.setStatus(Payment.PaymentStatus.PENDING);

        PaymentContext context = new PaymentContext(order, payment, null, 0.0);

        Payment result = strategy.process(context);

        assertSame(payment, result);
    }

    @Test
    void testProcess_HandlesNullOrder() {
        CODPaymentStrategy strategy = new CODPaymentStrategy();

        Payment payment = new Payment();
        payment.setPaymentMethod(Payment.PaymentMethod.CASH_ON_DELIVERY);
        payment.setStatus(Payment.PaymentStatus.PENDING);

        PaymentContext context = new PaymentContext(null, payment, null, 0.0);

        Payment result = strategy.process(context);

        assertEquals(Payment.PaymentStatus.PENDING, result.getStatus());
    }
}
