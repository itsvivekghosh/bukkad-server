package com.bhukkad.payment.strategy;

import com.bhukkad.entity.Order;
import com.bhukkad.entity.Payment;
import com.bhukkad.payment.PaymentGateway;
import com.bhukkad.payment.PaymentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GatewayPaymentStrategyTest {

    @Mock
    private PaymentGateway paymentGateway;
    @Mock
    private PaymentProperties paymentProperties;
    @Mock
    private PaymentProperties.Razorpay razorpay;

    private GatewayPaymentStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new GatewayPaymentStrategy(paymentGateway, paymentProperties);
    }

    @Test
    void testProcess_Success_CreatesPaymentAndCapture() {
        when(paymentProperties.getRazorpay()).thenReturn(razorpay);
        when(razorpay.getCurrency()).thenReturn("INR");

        Order order = new Order();
        order.setOrderNumber("ORD-12345678");

        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setPaymentMethod(Payment.PaymentMethod.CREDIT_CARD);
        payment.setGatewayAmount(500.0);

        PaymentContext context = new PaymentContext(order, payment, "idem-key", 500.0);

        when(paymentGateway.createOrder(any())).thenReturn(
                new PaymentGateway.GatewayOrderResult("order_123", "{}"));
        when(paymentGateway.capturePayment(any())).thenReturn(
                new PaymentGateway.GatewayPaymentResult("pay_123", "txn_123", true, "{}"));

        Payment result = strategy.process(context);

        assertNotNull(result);
        assertEquals(Payment.PaymentStatus.COMPLETED, result.getStatus());
        assertEquals("order_123", result.getGatewayOrderId());
        assertEquals("pay_123", result.getGatewayPaymentId());
        assertEquals("txn_123", result.getTransactionId());
        assertNotNull(result.getCompletedAt());

        verify(paymentGateway).createOrder(any());
        verify(paymentGateway).capturePayment(any());
    }

    @Test
    void testProcess_Failure_MarksPaymentFailed() {
        Order order = new Order();
        order.setOrderNumber("ORD-12345678");

        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setPaymentMethod(Payment.PaymentMethod.UPI);
        payment.setGatewayAmount(500.0);
        payment.setGatewayOrderId("order_123");

        PaymentContext context = new PaymentContext(order, payment, "idem-key", 500.0);

        when(paymentGateway.capturePayment(any())).thenReturn(
                new PaymentGateway.GatewayPaymentResult("pay_123", "txn_123", false, "{}"));

        Payment result = strategy.process(context);

        assertEquals(Payment.PaymentStatus.FAILED, result.getStatus());
        assertNull(result.getCompletedAt());
        verify(paymentGateway, never()).createOrder(any());
    }

    @Test
    void testProcess_NonGatewayMethod_ReturnsUnchanged() {
        Order order = new Order();
        order.setOrderNumber("ORD-12345678");

        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setPaymentMethod(Payment.PaymentMethod.CASH_ON_DELIVERY);

        PaymentContext context = new PaymentContext(order, payment, null, 0.0);

        Payment result = strategy.process(context);

        assertEquals(payment, result);
        verify(paymentGateway, never()).createOrder(any());
        verify(paymentGateway, never()).capturePayment(any());
    }

    @Test
    void testProcess_GatewayException_ThrowsBusinessException() {
        when(paymentProperties.getRazorpay()).thenReturn(razorpay);
        when(razorpay.getCurrency()).thenReturn("INR");

        Order order = new Order();
        order.setOrderNumber("ORD-12345678");

        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setPaymentMethod(Payment.PaymentMethod.CREDIT_CARD);
        payment.setGatewayAmount(500.0);

        PaymentContext context = new PaymentContext(order, payment, "idem-key", 500.0);

        when(paymentGateway.createOrder(any())).thenReturn(
                new PaymentGateway.GatewayOrderResult("order_123", "{}"));
        when(paymentGateway.capturePayment(any()))
                .thenThrow(new RuntimeException("Gateway timeout"));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> strategy.process(context));
        assertTrue(ex.getMessage().contains("Gateway timeout"));
        verify(paymentGateway).createOrder(any());
        verify(paymentGateway).capturePayment(any());
    }
}
