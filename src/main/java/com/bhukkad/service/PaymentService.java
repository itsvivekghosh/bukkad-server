package com.bhukkad.service;

import com.bhukkad.dto.response.PaymentResponse;
import com.bhukkad.entity.Payment;

public interface PaymentService {
    Payment createPayment(Long orderId, String paymentMethod, String idempotencyKey);
    Payment processPayment(Long paymentId, String idempotencyKey);
    Payment getPaymentByOrderId(Long orderId);
    PaymentResponse getPaymentForOrder(Long orderId);
    void refundPayment(Long paymentId);
    void completeWebhookPayment(String gatewayOrderId, String gatewayPaymentId);
}