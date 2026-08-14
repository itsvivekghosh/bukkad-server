package com.bhukkad.payment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConditionalOnProperty(name = "app.payment.razorpay.enabled", havingValue = "false", matchIfMissing = true)
public class SimulatedPaymentGateway implements PaymentGateway {

    @Override
    public GatewayOrderResult createOrder(GatewayOrderRequest request) {
        return GatewayOrderResult.builder()
                .gatewayOrderId("SIM-ORD-" + UUID.randomUUID())
                .rawResponse("{\"simulated\":true}")
                .build();
    }

    @Override
    public GatewayPaymentResult capturePayment(GatewayCaptureRequest request) {
        return GatewayPaymentResult.builder()
                .gatewayPaymentId("SIM-PAY-" + UUID.randomUUID())
                .transactionId("TXN-" + UUID.randomUUID())
                .success(true)
                .rawResponse("{\"simulated\":true}")
                .build();
    }

    @Override
    public GatewayRefundResult refundPayment(GatewayRefundRequest request) {
        return GatewayRefundResult.builder()
                .refundId("SIM-REF-" + UUID.randomUUID())
                .success(true)
                .rawResponse("{\"simulated\":true}")
                .build();
    }

    @Override
    public boolean verifyWebhookSignature(String payload, String signature) {
        return true;
    }
}
