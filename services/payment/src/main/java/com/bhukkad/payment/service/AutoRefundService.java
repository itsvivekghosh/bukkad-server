package com.bhukkad.payment.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.payment.domain.Payment;
import com.bhukkad.payment.domain.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refund policy + auto-refund (port of monolith {@code RefundPolicyService} /
 * {@code AutoRefundService}). A settled payment can be refunded exactly once.
 */
@Service
@RequiredArgsConstructor
public class AutoRefundService {

    private final PaymentRepository paymentRepository;
    private final PaymentEventPublisher eventPublisher;

    @Transactional
    public Payment refund(Long paymentId, String reason) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found: " + paymentId));
        if (!Payment.STATUS_SETTLED.equals(payment.getStatus())) {
            throw new BusinessException("Only settled payments can be refunded");
        }
        payment.setStatus(Payment.STATUS_REFUNDED);
        paymentRepository.save(payment);
        eventPublisher.paymentSettled(payment.getId(), payment.getOrderId(),
                payment.getCustomerId(), payment.getAmount());
        return payment;
    }
}