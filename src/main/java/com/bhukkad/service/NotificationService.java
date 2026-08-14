package com.bhukkad.service;

public interface NotificationService {
    void sendOrderConfirmation(Long orderId);
    void sendOrderStatusUpdate(Long orderId, String status);
    void sendDeliveryAssignment(Long orderId, Long agentId);
    void sendEmailVerification(String email, String token);
    void sendPasswordReset(String email, String token);
    void sendPaymentRefunded(Long orderId, Double amount);
}