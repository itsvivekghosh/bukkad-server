package com.bhukkad.delivery.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.delivery.config.DeliveryMatchingProperties;
import com.bhukkad.delivery.domain.DeliveryAgent;
import com.bhukkad.delivery.domain.DeliveryAgentRepository;
import com.bhukkad.delivery.domain.DeliveryAssignment;
import com.bhukkad.delivery.domain.DeliveryAssignmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(DeliveryMatchingProperties.class)
public class DeliveryService {

    private final DeliveryAgentRepository agentRepository;
    private final DeliveryAssignmentRepository assignmentRepository;
    private final DeliveryEventPublisher eventPublisher;
    private final RiderProximityMatcher proximityMatcher;
    private final DeliveryMatchingProperties matchingProperties;

    /**
     * Assigns the order to an agent atomically (audit B10). The old
     * check-then-act (find → pick agent → save) let two racing callers both
     * pass the existence pre-check and insert duplicate assignments; the
     * {@code uq_delivery_assignments_order} constraint arbitrates via
     * INSERT ... ON CONFLICT DO NOTHING. Callers keep the existing contract:
     * a duplicate assign throws {@link BusinessException} "already assigned"
     * (HTTP 400 via the platform error handler).
     *
     * <p>Agent selection (PERF-4 / ADR-003): candidates are tried in
     * proximity order over the riders' last-known GPS positions
     * ({@code rider_location_updates}; plain SQL haversine — PostGIS stays
     * deferred), each admitted by the conditional load-cap UPDATE
     * {@code SET active_load = active_load + 1 WHERE id = ? AND active_load < cap}
     * so a rider is never pushed past the cap no matter how many dispatchers
     * race. When the matcher is off (default, {@code app.delivery.geo-matching.enabled})
     * or no rider has fresh coordinates, selection falls back to the legacy
     * first-active-agent pick — still admitted through the same load cap.
     * The counter is released in {@link #markDelivered(Long)}.</p>
     */
    @Transactional
    public DeliveryAssignment assign(Long orderId) {
        // P3 / ADR-003: proximity matching is live behind
        // app.delivery.geo-matching.enabled, but the mesh call site carries no
        // anchor coordinates (DeliveryController is frozen for this batch;
        // order→delivery coordinate hand-off is another batch's data flow).
        // Without an anchor the matcher returns empty and this stays on the
        // legacy pick — byte-identical behavior until the anchor arrives.
        return assign(orderId, null, null);
    }

    /**
     * Anchor-aware overload (ADR-003 nearest-rider selection): when
     * geo-matching is enabled AND an anchor point is supplied, the closest
     * ACTIVE rider within the freshness window and below the active-assignment
     * cap is preferred. No match (flag off / no anchor / no fresh candidate /
     * every eligible rider at cap) falls back to {@code findFirstByIsActiveTrue}
     * — matching the ADR's explicit "findFirstByIsActiveTrue stays as fallback".
     * Either way, uniqueness remains arbitrated by the conditional insert.
     */
    @Transactional
    public DeliveryAssignment assign(Long orderId, Double anchorLat, Double anchorLng) {
        // Fast path preserves the friendly contract for the common repeat
        // call without touching the agent pool; it is NOT the arbiter.
        if (assignmentRepository.findByOrderId(orderId).isPresent()) {
            throw new BusinessException("Order already assigned: " + orderId);
        }

        // ADR-003: nearest eligible fresh rider wins when the matcher finds
        // one; otherwise pick within the active-load cap (PERF-4), legacy
        // first-active order as the final fallback inside that picker.
        long agentId = proximityMatcher.nearestEligible(anchorLat, anchorLng)
                .map(DeliveryAgent::getId)
                .orElseGet(() -> selectAgentIdWithinCap(orderId));

        LocalDateTime now = LocalDateTime.now();
        int inserted = assignmentRepository.insertIfAbsent(orderId, agentId,
                DeliveryAssignment.STATUS_ASSIGNED, now);
        if (inserted == 0) {
            // Lost the race (or a repeat call slipped past the pre-check): the
            // winning assignment is committed (or about to be) — same business
            // answer as the pre-check. Our load-cap increment is rolled back
            // with this transaction, so the rider's counter stays honest.
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
     * "Already delivered", which amplified retries into 400/500 noise). The
     * winning transition also releases the rider's active-load slot taken at
     * assign time.
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
        agentRepository.decrementActiveLoad(assignment.getAgentId());
        eventPublisher.orderDelivered(orderId, assignment.getAgentId());
        return assignment;
    }

    /**
     * Picks the next dispatchable agent, admitting each candidate through the
     * conditional active-load cap (the cap, not the in-memory pick, is the
     * single-winner arbiter under concurrency). Positioned candidates (fresh
     * last-known GPS) are tried first when geo matching is enabled; the
     * legacy first-active-agent pick is the fallback. On cap exhaustion the
     * order is re-checked: a racer that consumed the last slot may have
     * committed the assignment, and the honest business answer is then
     * "already assigned", not "no agent available".
     */
    private Long selectAgentIdWithinCap(Long orderId) {
        int cap = matchingProperties.getActiveLoadCap();
        boolean candidatesExamined = false;

        for (Long candidateId : candidateIds()) {
            candidatesExamined = true;
            if (agentRepository.incrementActiveLoadWithinCap(candidateId, cap) == 1) {
                return candidateId;
            }
            log.debug("ASSIGN_CANDIDATE_AT_CAP | agentId={} | cap={}", candidateId, cap);
        }
        if (candidatesExamined && assignmentRepository.findByOrderId(orderId).isPresent()) {
            throw new BusinessException("Order already assigned: " + orderId);
        }
        throw new BusinessException("No active delivery agent available");
    }

    /**
     * Dispatch candidate order: proximity-ranked positioned riders when the
     * geo matcher is enabled (recency-ranked while the order's coordinates
     * are not known to the delivery service — see the repository query),
     * otherwise the legacy active-agent pick.
     */
    private List<Long> candidateIds() {
        if (matchingProperties.getGeoMatching().isEnabled()) {
            LocalDateTime cutoff = LocalDateTime.now()
                    .minusMinutes(matchingProperties.getPositionFreshnessMinutes());
            List<Long> positioned = agentRepository
                    .findPositionedCandidates(null, null, cutoff,
                            matchingProperties.getCandidateLimit())
                    .stream()
                    .map(DeliveryAgentRepository.RiderCandidate::getId)
                    .toList();
            if (!positioned.isEmpty()) {
                return positioned;
            }
            log.debug("ASSIGN_NO_POSITIONED_RIDERS | falling back to active-agent pick");
        }
        return agentRepository.findByIsActiveTrueOrderByIdAsc().stream()
                .map(DeliveryAgent::getId)
                .toList();
    }
}
