package com.bhukkad.payment.strategy;

import com.bhukkad.entity.Order;
import com.bhukkad.entity.Payment;
import com.bhukkad.payment.PaymentGateway;
import com.bhukkad.payment.PaymentProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class GatewayPaymentStrategy implements PaymentStrategy {

    private final PaymentGateway paymentGateway;
    private final PaymentProperties paymentProperties;

    @Override
    @Transactional
    public Payment process(PaymentContext context) {
        Payment payment = context.payment();
        Order order = context.order();

        if (!requiresGateway(payment.getPaymentMethod())) {
            return payment;
        }

        if (payment.getGatewayOrderId() == null || payment.getGatewayOrderId().isBlank()) {
            PaymentGateway.GatewayOrderResult gatewayOrder = paymentGateway.createOrder(
                    PaymentGateway.GatewayOrderRequest.builder()
                            .amount(context.gatewayAmount())
                            .currency(paymentProperties.getRazorpay().getCurrency())
                            .receipt(order.getOrderNumber())
                            .idempotencyKey(context.idempotencyKey())
                            .build());
            payment.setGatewayOrderId(gatewayOrder.gatewayOrderId());
            payment.setPaymentGatewayResponse(gatewayOrder.rawResponse());
        }

        PaymentGateway.GatewayPaymentResult result = paymentGateway.capturePayment(
                PaymentGateway.GatewayCaptureRequest.builder()
                        .gatewayOrderId(payment.getGatewayOrderId())
                        .amount(context.gatewayAmount())
                        .idempotencyKey(context.idempotencyKey())
                        .build());

        payment.setGatewayPaymentId(result.gatewayPaymentId());
        payment.setTransactionId(result.transactionId());
        payment.setPaymentGatewayResponse(result.rawResponse());
        payment.setStatus(result.success()
                ? Payment.PaymentStatus.COMPLETED
                : Payment.PaymentStatus.FAILED);
        if (result.success()) {
            payment.setCompletedAt(LocalDateTime.now());
        }
        return payment;
    }

    private boolean requiresGateway(Payment.PaymentMethod method) {
        return method == Payment.PaymentMethod.CREDIT_CARD
                || method == Payment.PaymentMethod.DEBIT_CARD
                || method == Payment.PaymentMethod.UPI
                || method == Payment.PaymentMethod.NET_BANKING;
    }
}
