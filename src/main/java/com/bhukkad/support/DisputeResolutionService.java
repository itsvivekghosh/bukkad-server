package com.bhukkad.support;

import com.bhukkad.dto.request.DisputeRequest;
import com.bhukkad.dto.request.DisputeResolveRequest;
import com.bhukkad.dto.response.DisputeResponse;
import com.bhukkad.entity.Dispute;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.Payment;
import com.bhukkad.entity.User;
import com.bhukkad.entity.WalletTransaction;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.DisputeRepository;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.repository.UserRepository;
import com.bhukkad.util.PriceCalculator;
import com.bhukkad.wallet.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Evidence-based dispute resolution (Automated Dispute Resolution).
 *
 * <p>When a customer files a dispute the service immediately attempts to auto-resolve
 * it from evidence alone:
 * <ul>
 *   <li><b>ORDER_NOT_RECEIVED</b> with customer evidence on a delivered, paid order
 *       &rarr; automatic {@code FULL_REFUND}.</li>
 *   <li><b>LATE_DELIVERY</b> with delivery more than {@code lateThresholdMinutes}
 *       past estimate &rarr; automatic {@code PARTIAL_REFUND} (10%, capped).</li>
 *   <li>Everything else &rarr; {@code UNDER_REVIEW} for the manual queue.</li>
 * </ul>
 * Admins can additionally resolve manually, and a sweep endpoint re-runs the
 * auto-resolution rules over any disputes that are still open.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DisputeResolutionService {

    private static final double LATE_DELIVERY_REFUND_PERCENT = 0.10;
    private static final double MAX_LATE_DELIVERY_REFUND = 100.0;

    private final DisputeRepository disputeRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final WalletService walletService;

    @Value("${app.dispute.late-threshold-minutes:30}")
    private long lateThresholdMinutes;

    @Transactional
    public DisputeResponse fileDispute(Long customerId, Long orderId, DisputeRequest request) {
        Order order = orderRepository.findByIdWithDetails(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        if (order.getCustomer() == null || !order.getCustomer().getId().equals(customerId)) {
            throw new BusinessException("Order does not belong to this customer");
        }
        if (disputeRepository.existsByOrderId(orderId)) {
            throw new BusinessException("A dispute already exists for this order");
        }
        if (Order.OrderStatus.CANCELLED.equals(order.getStatus())
                || Order.OrderStatus.REFUNDED.equals(order.getStatus())) {
            throw new BusinessException("Cannot dispute a cancelled or refunded order");
        }

        Dispute.DisputeType type;
        try {
            type = Dispute.DisputeType.valueOf(request.getType().trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException("Invalid dispute type: " + request.getType());
        }

        Dispute dispute = new Dispute();
        dispute.setOrder(order);
        dispute.setType(type);
        dispute.setCustomerEvidence(request.getCustomerEvidence());
        dispute.setStatus(Dispute.DisputeStatus.OPEN);
        dispute = disputeRepository.save(dispute);

        attemptAutoResolution(dispute);
        return toResponse(disputeRepository.save(dispute));
    }

    @Transactional(readOnly = true)
    public List<DisputeResponse> listForAdmin() {
        return disputeRepository.findAll().stream()
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DisputeResponse> listForCustomer(Long customerId) {
        return disputeRepository.findByOrderCustomerIdOrderByCreatedAtDesc(customerId)
                .stream()
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
            resolution = Dispute.DisputeResolution.valueOf(request.getResolution().trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException("Invalid resolution: " + request.getResolution());
        }

        double refund = resolveRefundAmount(dispute, resolution, request.getRefundAmount());
        if (refund > 0) {
            applyRefund(dispute, refund);
        }

        User admin = userRepository.findById(adminId)
                .orElseThrow(() -> new ResourceNotFoundException("Admin not found"));
        dispute.setResolution(resolution);
        dispute.setStatus(Dispute.DisputeStatus.MANUAL_RESOLVED);
        dispute.setResolutionNotes(request.getNotes());
        dispute.setRefundAmount(refund > 0 ? refund : null);
        dispute.setResolvedBy(admin);
        dispute.setResolvedAt(LocalDateTime.now());
        return toResponse(disputeRepository.save(dispute));
    }

    /**
     * Re-runs the evidence-based auto-resolution rules over every open dispute.
     *
     * @return number of disputes resolved by this sweep
     */
    @Transactional
    public int triggerAutoResolution() {
        List<Dispute> open = disputeRepository.findByStatusInOrderByCreatedAtAsc(List.of(
                Dispute.DisputeStatus.OPEN, Dispute.DisputeStatus.UNDER_REVIEW));
        int resolved = 0;
        for (Dispute dispute : open) {
            if (attemptAutoResolution(dispute)) {
                disputeRepository.save(dispute);
                resolved++;
            }
        }
        return resolved;
    }

    private boolean attemptAutoResolution(Dispute dispute) {
        Order order = dispute.getOrder();
        Dispute.DisputeType type = dispute.getType();
        boolean hasEvidence = StringUtils.hasText(dispute.getCustomerEvidence());

        if (Dispute.DisputeType.ORDER_NOT_RECEIVED.equals(type)) {
            if (hasEvidence && isDelivered(order) && isPaid(order)) {
                double refund = PriceCalculator.roundToTwoDecimals(
                        order.getTotalAmount() != null ? order.getTotalAmount() : 0.0);
                if (refund > 0) {
                    applyRefund(dispute, refund);
                    dispute.setResolution(Dispute.DisputeResolution.FULL_REFUND);
                    dispute.setStatus(Dispute.DisputeStatus.AUTO_RESOLVED);
                    dispute.setRefundAmount(refund);
                    dispute.setResolvedAt(LocalDateTime.now());
                    dispute.setResolutionNotes("Auto-resolved: order not received with customer evidence on delivered, paid order");
                    return true;
                }
            }
        } else if (Dispute.DisputeType.LATE_DELIVERY.equals(type)) {
            if (hasEvidence && isDelivered(order) && isLate(order)) {
                double refund = computeLateDeliveryRefund(order);
                if (refund > 0) {
                    applyRefund(dispute, refund);
                    dispute.setResolution(Dispute.DisputeResolution.PARTIAL_REFUND);
                    dispute.setStatus(Dispute.DisputeStatus.AUTO_RESOLVED);
                    dispute.setRefundAmount(refund);
                    dispute.setResolvedAt(LocalDateTime.now());
                    dispute.setResolutionNotes("Auto-resolved: late delivery beyond threshold");
                    return true;
                }
            }
        }

        if (Dispute.DisputeStatus.OPEN.equals(dispute.getStatus())) {
            dispute.setStatus(Dispute.DisputeStatus.UNDER_REVIEW);
            dispute.setResolutionNotes("Queued for manual review: insufficient evidence or ineligible for auto-resolution");
            return true;
        }
        return false;
    }

    private boolean isDelivered(Order order) {
        return Order.OrderStatus.DELIVERED.equals(order.getStatus());
    }

    private boolean isPaid(Order order) {
        return order.getPayment() != null
                && Payment.PaymentStatus.COMPLETED.equals(order.getPayment().getStatus());
    }

    private boolean isLate(Order order) {
        if (order.getDeliveredAt() == null || order.getEstimatedDeliveryAt() == null) {
            return false;
        }
        long lateMinutes = Duration.between(order.getEstimatedDeliveryAt(), order.getDeliveredAt()).toMinutes();
        return lateMinutes > lateThresholdMinutes;
    }

    private double computeLateDeliveryRefund(Order order) {
        double base = PriceCalculator.roundToTwoDecimals(
                (order.getTotalAmount() != null ? order.getTotalAmount() : 0.0) * LATE_DELIVERY_REFUND_PERCENT);
        return PriceCalculator.roundToTwoDecimals(Math.min(base, MAX_LATE_DELIVERY_REFUND));
    }

    private double resolveRefundAmount(Dispute dispute, Dispute.DisputeResolution resolution, Double requested) {
        double total = dispute.getOrder().getTotalAmount() != null ? dispute.getOrder().getTotalAmount() : 0.0;
        return switch (resolution) {
            case FULL_REFUND -> {
                double amount = requested != null ? requested : total;
                if (amount <= 0 || amount > total) {
                    throw new BusinessException("Refund amount must be between 0 and order total");
                }
                yield amount;
            }
            case PARTIAL_REFUND -> {
                if (requested == null || requested <= 0 || requested >= total) {
                    throw new BusinessException("Partial refund amount must be greater than 0 and less than order total");
                }
                yield requested;
            }
            case NO_REFUND, CREDIT_ISSUED, ESCALATED -> 0.0;
        };
    }

    private void applyRefund(Dispute dispute, double amount) {
        Order order = dispute.getOrder();
        walletService.credit(
                order.getCustomer(),
                amount,
                WalletTransaction.TransactionType.ORDER_REFUND,
                order.getPayment(),
                "Refund for dispute on order " + order.getOrderNumber());
    }

    private Dispute findOrThrow(Long id) {
        return disputeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Dispute not found"));
    }

    private DisputeResponse toResponse(Dispute dispute) {
        Order order = dispute.getOrder();
        return DisputeResponse.builder()
                .id(dispute.getId())
                .orderId(order.getId())
                .orderNumber(order.getOrderNumber())
                .type(dispute.getType().name())
                .status(dispute.getStatus().name())
                .customerEvidence(dispute.getCustomerEvidence())
                .riderEvidence(dispute.getRiderEvidence())
                .restaurantEvidence(dispute.getRestaurantEvidence())
                .resolutionNotes(dispute.getResolutionNotes())
                .resolution(dispute.getResolution() != null ? dispute.getResolution().name() : null)
                .refundAmount(dispute.getRefundAmount())
                .resolvedBy(dispute.getResolvedBy() != null ? dispute.getResolvedBy().getId() : null)
                .resolvedAt(dispute.getResolvedAt() != null ? dispute.getResolvedAt().toString() : null)
                .createdAt(dispute.getCreatedAt() != null ? dispute.getCreatedAt().toString() : null)
                .build();
    }
}
