package com.bhukkad.payment.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.payment.domain.Payment;
import com.bhukkad.payment.domain.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * Refund policy + auto-refund (port of monolith {@code RefundPolicyService} /
 * {@code AutoRefundService}). A settled payment can be refunded exactly once.
 */
@Service
@RequiredArgsConstructor
public class AutoRefundService {

    /** Payment states the order-saga compensation step may refund. */
    private static final Set<String> SAGA_REFUNDABLE_STATUSES = Set.of(
            Payment.STATUS_PROCESSING, Payment.STATUS_PENDING_WALLET, Payment.STATUS_SETTLED);

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

    /**
     * Saga-compensation refund (internal charge/refund contract). Unlike the
     * admin-facing {@link #refund}, this also refunds not-yet-settled internal
     * charges (PROCESSING / PENDING_WALLET), and a replay against an already
     * REFUNDED row is an idempotent no-op returning the same payment — the
     * pessimistic row lock makes the REFUNDED check the exactly-once guard
     * for downstream compensation (e.g. wallet credit).
     */
    @Transactional
    public SagaRefund sagaRefund(Long paymentId, String reason) {
        Payment payment = paymentRepository.findByIdWithLock(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found: " + paymentId));
        String previousStatus = payment.getStatus();
        if (Payment.STATUS_REFUNDED.equals(previousStatus)) {
            return new SagaRefund(payment, null);
        }
        if (!SAGA_REFUNDABLE_STATUSES.contains(previousStatus)) {
            throw new BusinessException("Payment in status " + previousStatus + " cannot be refunded");
        }
        payment.setStatus(Payment.STATUS_REFUNDED);
        paymentRepository.save(payment);
        eventPublisher.paymentSettled(payment.getId(), payment.getOrderId(),
                payment.getCustomerId(), payment.getAmount());
        return new SagaRefund(payment, previousStatus);
    }

    /**
     * Saga refund outcome; {@code previousStatus} is {@code null} when the row
     * was already refunded (replay), letting callers compensate at most once.
     */
    public record SagaRefund(Payment payment, String previousStatus) {
    }
}