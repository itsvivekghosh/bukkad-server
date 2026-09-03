package com.bhukkad.order.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.common.event.DisputeEvents;
import com.bhukkad.common.event.PlatformEventPublisher;
import com.bhukkad.order.api.DisputeRequest;
import com.bhukkad.order.api.DisputeResolveRequest;
import com.bhukkad.order.api.DisputeResponse;
import com.bhukkad.order.domain.Dispute;
import com.bhukkad.order.domain.DisputeRepository;
import com.bhukkad.order.domain.Order;
import com.bhukkad.order.domain.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Evidence-based dispute resolution (extracted from monolith
 * DisputeResolutionService). Auto-resolves ORDER_NOT_RECEIVED and LATE_DELIVERY
 * when evidence is sufficient; everything else goes UNDER_REVIEW for manual
 * resolution. Refund execution is delegated to the payment service via a
 * {@link DisputeEvents.DisputeResolved} event.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DisputeResolutionService {

    private static final double LATE_DELIVERY_REFUND_PERCENT = 0.10;
    private static final BigDecimal MAX_LATE_DELIVERY_REFUND = BigDecimal.valueOf(100.0);

    private final DisputeRepository disputeRepository;
    private final OrderRepository orderRepository;
    private final PlatformEventPublisher eventPublisher;

    @Value("${app.dispute.late-threshold-minutes:30}")
    private long lateThresholdMinutes;

    @Transactional
    public DisputeResponse fileDispute(Long customerId, Long orderId, DisputeRequest request) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));

        if (!order.getCustomerId().equals(customerId)) {
            throw new BusinessException("Order does not belong to this customer");
        }
        if (disputeRepository.existsByOrderId(orderId)) {
            throw new BusinessException("A dispute already exists for this order");
        }
        if (Order.STATUS_CANCELLED.equals(order.getStatus())) {
            throw new BusinessException("Cannot dispute a cancelled order");
        }

        Dispute.DisputeType type;
        try {
            type = Dispute.DisputeType.valueOf(request.type().trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException("Invalid dispute type: " + request.type());
        }

        Dispute dispute = new Dispute();
        dispute.setOrderId(orderId);
        dispute.setType(type);
        dispute.setCustomerEvidence(request.customerEvidence());
        dispute.setStatus(Dispute.DisputeStatus.OPEN);
        dispute = disputeRepository.save(dispute);

        attemptAutoResolution(dispute, order);
        return toResponse(disputeRepository.save(dispute));
    }

    @Transactional(readOnly = true)
    public List<DisputeResponse> listForAdmin() {
        return disputeRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DisputeResponse> listForCustomer(Long customerId) {
        // Find all orders for this customer, then their disputes
        List<Long> orderIds = orderRepository.findByCustomerId(customerId).stream()
                .map(Order::getId)
                .toList();
        if (orderIds.isEmpty()) {
            return List.of();
        }
        return disputeRepository.findByOrderIdInOrderByCreatedAtDesc(orderIds).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public DisputeResponse getById(Long disputeId) {
        return toResponse(findOrThrow(disputeId));
    }

    @Transactional
    public DisputeResponse manualResolve(Long adminId, Long disputeId, DisputeResolveRequest request) {
        Dispute dispute = findOrThrow(disputeId);
        if (Dispute.DisputeStatus.CLOSED.equals(dispute.getStatus())) {
            throw new BusinessException("Dispute is already closed");
        }

        Dispute.DisputeResolution resolution;
        try {
            resolution = Dispute.DisputeResolution.valueOf(request.resolution().trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException("Invalid resolution: " + request.resolution());
        }

        Order order = orderRepository.findById(dispute.getOrderId())
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        BigDecimal refund = resolveRefundAmount(order, resolution, request.refundAmount());

        dispute.setResolution(resolution);
        dispute.setStatus(Dispute.DisputeStatus.MANUAL_RESOLVED);
        dispute.setResolutionNotes(request.notes());
        dispute.setRefundAmount(refund.compareTo(BigDecimal.ZERO) > 0 ? refund : null);
        dispute.setResolvedBy(adminId);
        dispute.setResolvedAt(LocalDateTime.now());
        dispute = disputeRepository.save(dispute);

        if (refund.compareTo(BigDecimal.ZERO) > 0) {
            publishRefundEvent(dispute, order, refund, resolution);
        }
        return toResponse(dispute);
    }

    /**
     * Re-runs auto-resolution rules over every open dispute.
     *
     * @return number of disputes resolved in this sweep
     */
    @Transactional
    public int triggerAutoResolution() {
        List<Dispute> open = disputeRepository.findByStatusInOrderByCreatedAtAsc(
                List.of(Dispute.DisputeStatus.OPEN, Dispute.DisputeStatus.UNDER_REVIEW));
        int resolved = 0;
        for (Dispute dispute : open) {
            Order order = orderRepository.findById(dispute.getOrderId()).orElse(null);
            if (order == null) continue;
            if (attemptAutoResolution(dispute, order)) {
                disputeRepository.save(dispute);
                resolved++;
            }
        }
        return resolved;
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Attempts to auto-resolve a dispute. Returns true if the dispute status
     * changed (OPEN → AUTO_RESOLVED or OPEN → UNDER_REVIEW).
     */
    private boolean attemptAutoResolution(Dispute dispute, Order order) {
        Dispute.DisputeType type = dispute.getType();
        boolean hasEvidence = StringUtils.hasText(dispute.getCustomerEvidence());

        if (Dispute.DisputeType.ORDER_NOT_RECEIVED.equals(type)) {
            if (hasEvidence && Order.STATUS_DELIVERED.equals(order.getStatus())) {
                BigDecimal refund = order.getTotalAmount() != null
                        ? order.getTotalAmount().setScale(2, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
                if (refund.compareTo(BigDecimal.ZERO) > 0) {
                    dispute.setStatus(Dispute.DisputeStatus.AUTO_RESOLVED);
                    dispute.setResolution(Dispute.DisputeResolution.FULL_REFUND);
                    dispute.setRefundAmount(refund);
                    dispute.setResolvedAt(LocalDateTime.now());
                    dispute.setResolutionNotes(
                            "Auto-resolved: order not received with customer evidence on delivered order");
                    publishRefundEvent(dispute, order, refund, Dispute.DisputeResolution.FULL_REFUND);
                    return true;
                }
            }
        } else if (Dispute.DisputeType.LATE_DELIVERY.equals(type)) {
            if (hasEvidence && isDelivered(order) && isLate(order)) {
                BigDecimal refund = computeLateDeliveryRefund(order);
                if (refund.compareTo(BigDecimal.ZERO) > 0) {
                    dispute.setStatus(Dispute.DisputeStatus.AUTO_RESOLVED);
                    dispute.setResolution(Dispute.DisputeResolution.PARTIAL_REFUND);
                    dispute.setRefundAmount(refund);
                    dispute.setResolvedAt(LocalDateTime.now());
                    dispute.setResolutionNotes("Auto-resolved: late delivery beyond threshold");
                    publishRefundEvent(dispute, order, refund, Dispute.DisputeResolution.PARTIAL_REFUND);
                    return true;
                }
            }
        }

        if (Dispute.DisputeStatus.OPEN.equals(dispute.getStatus())) {
            dispute.setStatus(Dispute.DisputeStatus.UNDER_REVIEW);
            dispute.setResolutionNotes(
                    "Queued for manual review: insufficient evidence or ineligible for auto-resolution");
            return true;
        }
        return false;
    }

    private boolean isDelivered(Order order) {
        return Order.STATUS_DELIVERED.equals(order.getStatus());
    }

    private boolean isLate(Order order) {
        if (order.getDeliveredAt() == null || order.getEstimatedDeliveryAt() == null) {
            return false;
        }
        long lateMinutes = ChronoUnit.MINUTES.between(
                order.getEstimatedDeliveryAt(), order.getDeliveredAt());
        return lateMinutes > lateThresholdMinutes;
    }

    private BigDecimal computeLateDeliveryRefund(Order order) {
        BigDecimal total = order.getTotalAmount() != null ? order.getTotalAmount() : BigDecimal.ZERO;
        BigDecimal refund = total.multiply(BigDecimal.valueOf(LATE_DELIVERY_REFUND_PERCENT))
                .setScale(2, RoundingMode.HALF_UP);
        return refund.min(MAX_LATE_DELIVERY_REFUND);
    }

    private BigDecimal resolveRefundAmount(Order order, Dispute.DisputeResolution resolution,
                                           BigDecimal requested) {
        BigDecimal total = order.getTotalAmount() != null ? order.getTotalAmount() : BigDecimal.ZERO;
        return switch (resolution) {
            case FULL_REFUND -> {
                BigDecimal amount = requested != null ? requested : total;
                if (amount.compareTo(BigDecimal.ZERO) <= 0 || amount.compareTo(total) > 0) {
                    throw new BusinessException("Refund amount must be between 0 and order total");
                }
                yield amount;
            }
            case PARTIAL_REFUND -> {
                if (requested == null || requested.compareTo(BigDecimal.ZERO) <= 0
                        || requested.compareTo(total) >= 0) {
                    throw new BusinessException(
                            "Partial refund amount must be greater than 0 and less than order total");
                }
                yield requested;
            }
            case NO_REFUND, CREDIT_ISSUED, ESCALATED -> BigDecimal.ZERO;
        };
    }

    private void publishRefundEvent(Dispute dispute, Order order, BigDecimal refund,
                                    Dispute.DisputeResolution resolution) {
        eventPublisher.publish(new DisputeEvents.DisputeResolved(
                dispute.getId(),
                dispute.getOrderId(),
                order.getCustomerId(),
                refund.doubleValue(),
                resolution.name(),
                java.time.Instant.now()));
    }

    private Dispute findOrThrow(Long id) {
        return disputeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Dispute not found: " + id));
    }

    private DisputeResponse toResponse(Dispute dispute) {
        Order order = orderRepository.findById(dispute.getOrderId()).orElse(null);
        return new DisputeResponse(
                dispute.getId(),
                dispute.getOrderId(),
                order != null ? order.getOrderNumber() : null,
                dispute.getType().name(),
                dispute.getStatus().name(),
                dispute.getCustomerEvidence(),
                dispute.getRiderEvidence(),
                dispute.getRestaurantEvidence(),
                dispute.getResolutionNotes(),
                dispute.getResolution() != null ? dispute.getResolution().name() : null,
                dispute.getRefundAmount(),
                dispute.getResolvedBy(),
                dispute.getResolvedAt(),
                dispute.getCreatedAt());
    }
}