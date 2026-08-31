package com.bhukkad.payment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "app.payment.razorpay.enabled", havingValue = "false", matchIfMissing = true)
public class SimulatedPaymentGateway implements PaymentGateway {

    @Override
    public CompletableFuture<GatewayOrderResult> createOrder(GatewayOrderRequest request) {
        return CompletableFuture.completedFuture(
                GatewayOrderResult.builder()
                        .gatewayOrderId("SIM-ORD-" + UUID.randomUUID())
                        .rawResponse("{\"simulated\":true}")
                        .build());
    }

    @Override
    public CompletableFuture<GatewayPaymentResult> capturePayment(GatewayCaptureRequest request) {
        return CompletableFuture.completedFuture(
                GatewayPaymentResult.builder()
                        .gatewayPaymentId("SIM-PAY-" + UUID.randomUUID())
                        .transactionId("TXN-" + UUID.randomUUID())
                        .success(true)
                        .rawResponse("{\"simulated\":true}")
                        .build());
    }

    @Override
    public CompletableFuture<GatewayRefundResult> refundPayment(GatewayRefundRequest request) {
        return CompletableFuture.completedFuture(
                GatewayRefundResult.builder()
                        .refundId("SIM-REF-" + UUID.randomUUID())
                        .success(true)
                        .rawResponse("{\"simulated\":true}")
                        .build());
    }

    @Override
    public boolean verifyWebhookSignature(String payload, String signature) {
        // Fail closed on missing signature: simulated mode must not silently
        // accept forged webhooks. A non-blank signature is accepted because
        // simulated mode has no shared secret to validate against; production
        // deployments MUST configure the real Razorpay gateway (enforced by
        // SecretValidationConfig).
        return signature != null && !signature.isBlank();
    }
}
