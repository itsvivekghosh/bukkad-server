package com.bhukkad.payment;

import com.bhukkad.payment.PaymentGateway.GatewayCaptureRequest;
import com.bhukkad.payment.PaymentGateway.GatewayOrderRequest;
import com.bhukkad.payment.PaymentGateway.GatewayOrderResult;
import com.bhukkad.payment.PaymentGateway.GatewayPaymentResult;
import com.bhukkad.payment.PaymentGateway.GatewayRefundRequest;
import com.bhukkad.payment.PaymentGateway.GatewayRefundResult;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimulatedPaymentGatewayTest {

    private final SimulatedPaymentGateway gateway = new SimulatedPaymentGateway();

    @Test
    void createOrder_returnsSimulatedResult() {
        GatewayOrderResult result = gateway.createOrder(
                new GatewayOrderRequest(100.0, "INR", "receipt-1", "idem-1"))
                .join();
        assertNotNull(result.gatewayOrderId());
        assertTrue(result.gatewayOrderId().startsWith("SIM-ORD-"));
        assertTrue(result.rawResponse().contains("simulated"));
    }

    @Test
    void capturePayment_returnsSimulatedResult() {
        GatewayPaymentResult result = gateway.capturePayment(
                new GatewayCaptureRequest("ord-1", 100.0, "idem-1"))
                .join();
        assertTrue(result.success());
        assertNotNull(result.gatewayPaymentId());
    }

    @Test
    void refundPayment_returnsSimulatedResult() {
        GatewayRefundResult result = gateway.refundPayment(
                new GatewayRefundRequest("pay-1", 50.0, "idem-1"))
                .join();
        assertTrue(result.success());
        assertNotNull(result.refundId());
    }

    @Test
    void verifyWebhookSignature_rejectsNullSignature() {
        assertFalse(gateway.verifyWebhookSignature("payload", null));
    }

    @Test
    void verifyWebhookSignature_rejectsBlankSignature() {
        assertFalse(gateway.verifyWebhookSignature("payload", ""));
        assertFalse(gateway.verifyWebhookSignature("payload", "   "));
    }

    @Test
    void verifyWebhookSignature_acceptsNonBlankSignature() {
        assertTrue(gateway.verifyWebhookSignature("payload", "signature"));
    }
}
