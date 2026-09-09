package com.bhukkad.delivery.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.delivery.domain.DeliveryAgent;
import com.bhukkad.delivery.domain.DeliveryAgentRepository;
import com.bhukkad.delivery.domain.DeliveryAssignment;
import com.bhukkad.delivery.domain.DeliveryAssignmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryService {

    private final DeliveryAgentRepository agentRepository;
    private final DeliveryAssignmentRepository assignmentRepository;
    private final DeliveryEventPublisher eventPublisher;

    /**
     * Assigns the order to an agent atomically (audit B10). The old
     * check-then-act (find → pick agent → save) let two racing callers both
     * pass the existence pre-check and insert duplicate assignments; now the
     * {@code uq_delivery_assignments_order} constraint arbitrates via
     * INSERT ... ON CONFLICT DO NOTHING. Callers keep the existing contract:
     * a duplicate assign throws {@link BusinessException} "already assigned"
     * (HTTP 400 via the platform error handler).
     */
    @Transactional
    public DeliveryAssignment assign(Long orderId) {
        // Fast path preserves the friendly contract for the common repeat
        // call without touching the agent pool; it is NOT the arbiter.
        if (assignmentRepository.findByOrderId(orderId).isPresent()) {
            throw new BusinessException("Order already assigned: " + orderId);
        }

        // PERF-4.1 note: nearest-available rider matching (Redis GEOSEARCH over
        // geo:rider:locations + a conditional active_load cap) was evaluated
        // and NOT wired in: assign(orderId) has no order coordinates without a
        // new order-service client (new S2S infra, out of batch scope), and
        // delivery_agents has no active_load column to cap without its own
        // lifecycle decrement. findFirstByIsActiveTrue is kept as the matching
        // strategy per the audit guidance, with uniqueness now enforced atomically.
        DeliveryAgent agent = agentRepository.findFirstByIsActiveTrue()
                .orElseThrow(() -> new BusinessException("No active delivery agent available"));

        LocalDateTime now = LocalDateTime.now();
        int inserted = assignmentRepository.insertIfAbsent(orderId, agent.getId(),
                DeliveryAssignment.STATUS_ASSIGNED, now);
        if (inserted == 0) {
            // Lost the race (or a repeat call slipped past the pre-check): the
            // winning assignment is committed (or about to be) — same business
            // answer as the pre-check. Nothing was written by us, so the
            // rollback that follows this throw stays a no-op.
            throw new BusinessException("Order already assigned: " + orderId);
        }

        DeliveryAssignment assignment = assignmentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalStateException(
                        "Assignment disappeared after insert for order " + orderId));
        eventPublisher.deliveryAssigned(orderId, assignment.getAgentId());
        return assignment;
    }

    /**
     * Marks the order delivered idempotently (audit B10 TOCTOU). The
     * conditional UPDATE flips status→DELIVERED exactly once across racing
     * callers; OrderDelivered is published only for that 1-row transition, so
     * downstream consumers see one event per order. Duplicate calls are now a
     * successful no-op returning the assignment (previously threw
     * "Already delivered", which amplified retries into 400/500 noise).
     */
    @Transactional
    public DeliveryAssignment markDelivered(Long orderId) {
        LocalDateTime now = LocalDateTime.now();
        int updated = assignmentRepository.markDeliveredIfOpen(
                orderId, DeliveryAssignment.STATUS_DELIVERED, now);
        DeliveryAssignment assignment = assignmentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Assignment not found: " + orderId));
        if (updated == 0) {
            log.debug("MARK_DELIVERED_IDEMPOTENT | orderId={}", orderId);
            return assignment;
        }
        eventPublisher.orderDelivered(orderId, assignment.getAgentId());
        return assignment;
    }
}
