package com.bhukkad.service;

import com.bhukkad.entity.Payment;

public interface PaymentService {
    Payment createPayment(Long orderId, String paymentMethod);
    Payment processPayment(Long paymentId);
    Payment getPaymentByOrderId(Long orderId);
    void refundPayment(Long paymentId);
}