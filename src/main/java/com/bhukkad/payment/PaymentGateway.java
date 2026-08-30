package com.bhukkad.payment;

import lombok.Builder;

import java.util.concurrent.CompletableFuture;

public interface PaymentGateway {

    CompletableFuture<GatewayOrderResult> createOrder(GatewayOrderRequest request);

    CompletableFuture<GatewayPaymentResult> capturePayment(GatewayCaptureRequest request);

    CompletableFuture<GatewayRefundResult> refundPayment(GatewayRefundRequest request);

    boolean verifyWebhookSignature(String payload, String signature);

    @Builder
    record GatewayOrderRequest(
            double amount,
            String currency,
            String receipt,
            String idempotencyKey
    ) {}

    @Builder
    record GatewayOrderResult(
            String gatewayOrderId,
            String rawResponse
    ) {}

    @Builder
    record GatewayCaptureRequest(
            String gatewayOrderId,
            double amount,
            String idempotencyKey
    ) {}

    @Builder
    record GatewayPaymentResult(
            String gatewayPaymentId,
            String transactionId,
            boolean success,
            String rawResponse
    ) {}

    @Builder
    record GatewayRefundRequest(
            String gatewayPaymentId,
            double amount,
            String idempotencyKey
    ) {}

    @Builder
    record GatewayRefundResult(
            String refundId,
            boolean success,
            String rawResponse
    ) {}
}
