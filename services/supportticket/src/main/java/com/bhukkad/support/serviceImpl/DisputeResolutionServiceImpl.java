package com.bhukkad.support.serviceImpl;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.support.dto.request.DisputeRequest;
import com.bhukkad.support.dto.request.DisputeResolveRequest;
import com.bhukkad.support.dto.response.DisputeResponse;
import com.bhukkad.support.entity.Dispute;
import com.bhukkad.support.repository.DisputeRepository;
import com.bhukkad.support.wallet.WalletCreditClient;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class DisputeResolutionServiceImpl {
    private static final Logger log = LoggerFactory.getLogger(DisputeResolutionServiceImpl.class);
    private static final double LATE_DELIVERY_REFUND_PERCENT = 0.1;
    private static final double MAX_LATE_DELIVERY_REFUND = 100.0;
    private final DisputeRepository disputeRepository;
    private final WalletCreditClient walletService;
    @Value(value="${app.dispute.late-threshold-minutes:30}")
    private long lateThresholdMinutes;

    public DisputeResolutionServiceImpl(DisputeRepository disputeRepository, WalletCreditClient walletService) {
        this.disputeRepository = disputeRepository;
        this.walletService = walletService;
    }

    @Transactional
    public DisputeResponse fileDispute(Long customerId, Long orderId, DisputeRequest request) {
        Dispute.DisputeType type;
        if (this.disputeRepository.existsByOrderId(orderId)) {
            throw new BusinessException("A dispute already exists for this order");
        }
        try {
            type = Dispute.DisputeType.valueOf(request.getType().trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException("Invalid dispute type: " + request.getType());
        }
        Dispute dispute = new Dispute();
        dispute.setOrderId(orderId);
        dispute.setCustomerId(customerId);
        dispute.setType(type);
        dispute.setCustomerEvidence(request.getCustomerEvidence());
        dispute.setStatus(Dispute.DisputeStatus.OPEN);
        dispute = this.disputeRepository.save(dispute);
        this.attemptAutoResolution(dispute);
        return this.toResponse(this.disputeRepository.save(dispute));
    }

    @Transactional(readOnly=true)
    public List<DisputeResponse> listForAdmin() {
        return this.disputeRepository.findAll().stream().sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt())).map(this::toResponse).toList();
    }

    @Transactional(readOnly=true)
    public List<DisputeResponse> listForCustomer(Long customerId) {
        return this.disputeRepository.findByCustomerIdOrderByCreatedAtDesc(customerId).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly=true)
    public DisputeResponse getById(Long disputeId) {
        return this.toResponse(this.findOrThrow(disputeId));
    }

    @Transactional
    public DisputeResponse manualResolve(Long adminId, Long disputeId, DisputeResolveRequest request) {
        Dispute.DisputeResolution resolution;
        Dispute dispute = this.findOrThrow(disputeId);
        if (Dispute.DisputeStatus.CLOSED.equals(dispute.getStatus())) {
            throw new BusinessException("Dispute is already closed");
        }
        try {
            resolution = Dispute.DisputeResolution.valueOf(request.getResolution().trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException("Invalid resolution: " + request.getResolution());
        }
        double refund = this.resolveRefundAmount(dispute, resolution, request.getRefundAmount());
        if (refund > 0.0) {
            this.applyRefund(dispute, refund);
        }
        dispute.setResolution(resolution);
        dispute.setStatus(Dispute.DisputeStatus.MANUAL_RESOLVED);
        dispute.setResolutionNotes(request.getNotes());
        dispute.setRefundAmount(refund > 0.0 ? Double.valueOf(refund) : null);
        dispute.setResolvedById(adminId);
        dispute.setResolvedAt(LocalDateTime.now());
        return this.toResponse(this.disputeRepository.save(dispute));
    }

    @Transactional
    public int triggerAutoResolution() {
        List<Dispute> open = this.disputeRepository.findByStatusInOrderByCreatedAtAsc(
                List.of(Dispute.DisputeStatus.OPEN, Dispute.DisputeStatus.UNDER_REVIEW));
        int resolved = 0;
        for (Dispute dispute : open) {
            dispute.setStatus(Dispute.DisputeStatus.UNDER_REVIEW);
            this.disputeRepository.save(dispute);
            resolved++;
        }
        return resolved;
    }

    private void attemptAutoResolution(Dispute dispute) {
        // TODO: integrate with order-service via REST to inspect delivery timing
    }

    private void applyRefund(Dispute dispute, double amount) {
        // Order and payment details now live in the order service; refunds must be
        // triggered via an internal REST call once that endpoint exists.
        log.warn("REFUND_DEFERRED disputeId={} amount={} orderId={}", dispute.getId(), amount, dispute.getOrderId());
    }

    private double resolveRefundAmount(Dispute dispute, Dispute.DisputeResolution resolution, Double requestedRefund) {
        if (Dispute.DisputeResolution.FULL_REFUND.equals(resolution)) {
            return requestedRefund != null ? requestedRefund : 0.0;
        } else if (Dispute.DisputeResolution.PARTIAL_REFUND.equals(resolution)) {
            if (requestedRefund == null || requestedRefund <= 0) {
                return Math.min(MAX_LATE_DELIVERY_REFUND, 0.0);
            }
            return Math.min(requestedRefund, MAX_LATE_DELIVERY_REFUND);
        }
        return 0.0;
    }

    private Dispute findOrThrow(Long id) {
        return this.disputeRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Dispute not found"));
    }

    private DisputeResponse toResponse(Dispute dispute) {
        return new DisputeResponse(dispute.getId(), dispute.getOrderId(), null, dispute.getType().name(), dispute.getStatus().name(), dispute.getCustomerEvidence(), dispute.getRiderEvidence(), dispute.getRestaurantEvidence(), dispute.getResolutionNotes(), dispute.getResolution() != null ? dispute.getResolution().name() : null, dispute.getRefundAmount(), dispute.getResolvedById(), dispute.getResolvedAt() != null ? dispute.getResolvedAt().toString() : null, dispute.getCreatedAt() != null ? dispute.getCreatedAt().toString() : null);
    }
}
