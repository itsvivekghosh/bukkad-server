package com.bhukkad.payment.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.payment.domain.Dispute;
import com.bhukkad.payment.domain.DisputeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Payment dispute lifecycle (Priority 3): raise, resolve as refunded or
 * rejected. The money movement on "REFUNDED" would call the refund strategy;
 * this service owns the dispute state machine.
 */
@Service
@RequiredArgsConstructor
public class DisputeService {

    private final DisputeRepository disputeRepository;

    @Transactional
    public Dispute raise(Long paymentId, Long customerId, Long orderId, String reason, BigDecimal amount) {
        if (amount.signum() <= 0) {
            throw new BusinessException("Dispute amount must be positive");
        }
        Dispute dispute = new Dispute();
        dispute.setPaymentId(paymentId);
        dispute.setCustomerId(customerId);
        dispute.setOrderId(orderId);
        dispute.setReason(reason);
        dispute.setAmount(amount);
        dispute.setStatus(Dispute.STATUS_OPEN);
        return disputeRepository.save(dispute);
    }

    @Transactional
    public Dispute resolve(Long disputeId, boolean refund) {
        Dispute dispute = disputeRepository.findById(disputeId)
                .orElseThrow(() -> new ResourceNotFoundException("Dispute not found: " + disputeId));
        if (!Dispute.STATUS_OPEN.equals(dispute.getStatus())) {
            throw new BusinessException("Dispute already resolved");
        }
        dispute.setStatus(refund ? Dispute.STATUS_REFUNDED : Dispute.STATUS_REJECTED);
        dispute.setResolution(refund ? "Refunded" : "Rejected after review");
        return disputeRepository.save(dispute);
    }

    @Transactional(readOnly = true)
    public List<Dispute> byCustomer(Long customerId) {
        return disputeRepository.findByCustomerId(customerId);
    }
}