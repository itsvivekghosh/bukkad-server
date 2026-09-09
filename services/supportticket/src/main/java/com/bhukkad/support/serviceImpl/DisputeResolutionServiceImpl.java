package com.bhukkad.support.serviceImpl;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.support.dto.request.DisputeRequest;
import com.bhukkad.support.dto.request.DisputeResolveRequest;
import com.bhukkad.support.dto.OrderDetailDto;
import com.bhukkad.support.dto.response.DisputeResponse;
import com.bhukkad.support.entity.Dispute;
import com.bhukkad.support.repository.DisputeRepository;
import com.bhukkad.support.client.OrderServiceClient;
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
    private final OrderServiceClient orderServiceClient;
    @Value(value="${app.dispute.late-threshold-minutes:30}")
    private long lateThresholdMinutes;

    public DisputeResolutionServiceImpl(DisputeRepository disputeRepository, WalletCreditClient walletService, OrderServiceClient orderServiceClient) {
      this.disputeRepository = disputeRepository;
      this.walletService = walletService;
      this.orderServiceClient = orderServiceClient;
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
        dispute = this.applyRefund(dispute, refund);
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
        if (this.attemptAutoResolution(dispute)) {
          this.disputeRepository.save(dispute);
          resolved++;
        } else {
          this.disputeRepository.save(dispute);
        }
      }
      return resolved;
    }

    private boolean attemptAutoResolution(Dispute dispute) {
      OrderDetailDto order = orderServiceClient.getOrderDetails(dispute.getOrderId());
      if (order == null) {
        // If we cannot fetch the order, we cannot auto-resolve; leave as OPEN for manual review.
        return false;
      }

      Dispute.DisputeType type = dispute.getType();
      boolean hasEvidence = StringUtils.hasText(dispute.getCustomerEvidence());

      if (Dispute.DisputeType.ORDER_NOT_RECEIVED.equals(type)) {
        if (hasEvidence && "DELIVERED".equals(order.getStatus())) {
          // Full refund of order total
          double refundAmount = order.getTotalAmount().doubleValue();
          if (refundAmount > 0) {
            dispute = this.applyRefund(dispute, refundAmount);
          }
          dispute.setResolution(Dispute.DisputeResolution.FULL_REFUND);
          dispute.setResolutionNotes("Auto-resolved: order not received with customer evidence on delivered order");
          dispute.setStatus(Dispute.DisputeStatus.AUTO_RESOLVED);
          dispute.setRefundAmount(refundAmount);
          dispute.setResolvedAt(LocalDateTime.now());
          dispute.setResolvedById(null); // system auto-resolved
          return true;
        }
      } else if (Dispute.DisputeType.LATE_DELIVERY.equals(type)) {
        if (hasEvidence && order.getDeliveredAt() != null && order.getEstimatedDeliveryAt() != null) {
          long lateMinutes = java.time.Duration.between(order.getEstimatedDeliveryAt(), order.getDeliveredAt()).toMinutes();
          if (lateMinutes > lateThresholdMinutes) {
            // Partial refund: 10% of order total, capped at MAX_LATE_DELIVERY_REFUND
            double refundAmount = Math.min(order.getTotalAmount().doubleValue() * LATE_DELIVERY_REFUND_PERCENT, MAX_LATE_DELIVERY_REFUND);
            if (refundAmount > 0) {
              dispute = this.applyRefund(dispute, refundAmount);
            }
            dispute.setResolution(Dispute.DisputeResolution.PARTIAL_REFUND);
            dispute.setResolutionNotes("Auto-resolved: late delivery beyond threshold");
            dispute.setStatus(Dispute.DisputeStatus.AUTO_RESOLVED);
            dispute.setRefundAmount(refundAmount);
            dispute.setResolvedAt(LocalDateTime.now());
            dispute.setResolvedById(null); // system auto-resolved
            return true;
          }
        }
      }

      // If we reach here, the dispute does not meet auto-resolution criteria.
      // Set to UNDER_REVIEW for manual review.
      dispute.setStatus(Dispute.DisputeStatus.UNDER_REVIEW);
      return false;
    }

    /** Hard cap: a refund may never exceed the order's original total. */
    private double clampToOrderTotal(Dispute dispute, double amount) {
      OrderDetailDto order = orderServiceClient.getOrderDetails(dispute.getOrderId());
      if (order == null || order.getTotalAmount() == null) {
        throw new BusinessException(
                "Refund amount cannot be validated against the order total");
      }
      double total = order.getTotalAmount().doubleValue();
      if (total <= 0) {
        throw new BusinessException("Order total is not refundable");
      }
      return Math.min(amount, total);
    }

    private Dispute applyRefund(Dispute dispute, double amount) {
      if (amount <= 0) {
        return dispute;
      }
      walletService.credit(dispute.getCustomerId(), amount,
              dispute.getOrderId().toString(), null, "dispute-refund");
      return dispute;
    }

    private double resolveRefundAmount(Dispute dispute, Dispute.DisputeResolution resolution, Double requestedRefund) {
      if (Dispute.DisputeResolution.FULL_REFUND.equals(resolution)) {
        if (requestedRefund != null && requestedRefund > 0) {
          // NEVER above the order's paid total: an admin-supplied amount used
          // to be credited verbatim (fabricated-money vector, audit H-3).
          return clampToOrderTotal(dispute, requestedRefund);
        } else {
          // Lookup order total for full refund
          OrderDetailDto order = orderServiceClient.getOrderDetails(dispute.getOrderId());
          if (order != null) {
            return order.getTotalAmount().doubleValue();
          } else {
            // If we cannot get the order, we cannot determine the amount; require explicit amount.
            throw new BusinessException("Full refund requires a positive amount; "
                    + "auto-lookup of original payment amount requires order-service integration");
          }
        }
      } else if (Dispute.DisputeResolution.PARTIAL_REFUND.equals(resolution)) {
        if (requestedRefund != null && requestedRefund > 0) {
          return clampToOrderTotal(dispute, Math.min(requestedRefund, MAX_LATE_DELIVERY_REFUND));
        } else {
          // For partial refund without explicit amount, compute late delivery refund based on order total.
          OrderDetailDto order = orderServiceClient.getOrderDetails(dispute.getOrderId());
          if (order != null && order.getDeliveredAt() != null && order.getEstimatedDeliveryAt() != null) {
            long lateMinutes = java.time.Duration.between(order.getEstimatedDeliveryAt(), order.getDeliveredAt()).toMinutes();
            if (lateMinutes > lateThresholdMinutes) {
              double refundAmount = Math.min(order.getTotalAmount().doubleValue() * LATE_DELIVERY_REFUND_PERCENT, MAX_LATE_DELIVERY_REFUND);
              return refundAmount;
            }
          }
          // If we cannot compute, return 0.
          return 0.0;
        }
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
