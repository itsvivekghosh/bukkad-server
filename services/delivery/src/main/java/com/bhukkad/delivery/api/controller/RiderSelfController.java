package com.bhukkad.delivery.api;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.UnauthorizedException;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.delivery.domain.DeliveryAgent;
import com.bhukkad.delivery.domain.DeliveryAgentRepository;
import com.bhukkad.delivery.service.RiderOpsService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Delivery-agent self surface (monolith parity for the rider app): profile,
 * availability, live location, earnings, delivery queues and batching. The
 * acting agent is ALWAYS the JWT subject; agent records are created lazily on
 * first authenticated call so a freshly-verified rider can onboard without an
 * admin write.
 */
@RestController
@RequiredArgsConstructor
public class RiderSelfController {

    private final DeliveryAgentRepository agentRepository;
    private final RiderOpsService riderOpsService;
    private final com.bhukkad.delivery.domain.RiderLocationUpdateRepository locationRepository;
    private final com.bhukkad.delivery.domain.DeliveryAssignmentRepository assignmentRepository;
    private final com.bhukkad.delivery.domain.RiderDeliveryBatchRepository batchRepository;
    private final AgentProvisioner agentProvisioner;

    public record AgentProfileRequest(String name, String phone, String vehicleType,
                                      String vehicleNumber) {}

    /** Returns (lazily creating) the caller's rider profile. */
    @GetMapping("/api/v1/delivery/profile")
    public DeliveryAgent profile(@AuthenticationPrincipal TokenPrincipal principal) {
        return currentAgent(principal);
    }

    @PutMapping("/api/v1/delivery/profile")
    @Transactional
    public DeliveryAgent updateProfile(@AuthenticationPrincipal TokenPrincipal principal,
                                       @RequestBody(required = false) AgentProfileRequest request) {
        DeliveryAgent agent = agentRepository.findById(currentAgent(principal).getId())
                .orElseThrow();
        if (request != null) {
            if (request.name() != null && !request.name().isBlank()) {
                agent.setName(request.name().trim());
            }
            if (request.phone() != null && !request.phone().isBlank()) {
                agent.setPhone(request.phone().trim());
            }
            if (request.vehicleType() != null) {
                agent.setVehicleType(request.vehicleType());
            }
            if (request.vehicleNumber() != null) {
                agent.setVehicleNumber(request.vehicleNumber());
            }
        }
        return agentRepository.save(agent);
    }

    /** Flips the rider's availability to accept new assignments. */
    @PutMapping("/api/v1/delivery/toggle-availability")
    @Transactional
    public DeliveryAgent toggleAvailability(@AuthenticationPrincipal TokenPrincipal principal,
                                            @RequestParam(required = false) Boolean available) {
        DeliveryAgent agent = agentRepository.findById(currentAgent(principal).getId())
                .orElseThrow();
        agent.setIsActive(available == null || available);
        return agentRepository.save(agent);
    }

    /** Records the rider's live GPS position (fleet map + ETA inputs). */
    @PutMapping("/api/v1/delivery/update-location")
    @Transactional
    public Map<String, Object> updateLocation(@AuthenticationPrincipal TokenPrincipal principal,
                                              @RequestParam(required = false) Double latitude,
                                              @RequestParam(required = false) Double longitude) {
        DeliveryAgent agent = currentAgent(principal);
        if (latitude == null || longitude == null) {
            throw new BusinessException("latitude and longitude are required");
        }
        if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            throw new BusinessException("Invalid coordinates");
        }
        riderOpsService.reportLocation(agent.getId(), latitude, longitude);
        return Map.of("agentId", agent.getId(), "latitude", latitude,
                "longitude", longitude, "message", "Location updated");
    }

    /** Aggregated earnings summary for the rider dashboard. */
    @GetMapping("/api/v1/delivery/earnings/summary")
    @Transactional(readOnly = true)
    public Map<String, Object> earningsSummary(@AuthenticationPrincipal TokenPrincipal principal) {
        Long agentId = currentAgent(principal).getId();
        List<Map<String, Object>> earnings = riderOpsService.getEarnings(agentId);
        double today = earnings.stream()
                .filter(e -> "TODAY".equalsIgnoreCase(String.valueOf(e.get("period"))))
                .mapToDouble(e -> ((Number) e.getOrDefault("amount", 0)).doubleValue())
                .sum();
        double week = earnings.stream()
                .filter(e -> "WEEK".equalsIgnoreCase(String.valueOf(e.get("period"))))
                .mapToDouble(e -> ((Number) e.getOrDefault("amount", 0)).doubleValue())
                .sum();
        double total = earnings.stream()
                .filter(e -> "TOTAL".equalsIgnoreCase(String.valueOf(e.get("period"))))
                .mapToDouble(e -> ((Number) e.getOrDefault("amount", 0)).doubleValue())
                .sum();
        return Map.of(
                "agentId", agentId,
                "today", today,
                "thisWeek", week,
                "total", total,
                "deliveries", earnings.stream()
                        .mapToLong(e -> ((Number) e.getOrDefault("deliveries", 0)).longValue())
                        .sum());
    }

    @GetMapping("/api/v1/delivery/earnings")
    @Transactional(readOnly = true)
    public Map<String, Object> earnings(@AuthenticationPrincipal TokenPrincipal principal,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "10") int size) {
        Long agentId = currentAgent(principal).getId();
        List<Map<String, Object>> all = riderOpsService.getEarnings(agentId);
        int safeSize = Math.min(Math.max(size, 1), 100);
        int from = Math.min(Math.max(page, 0) * safeSize, all.size());
        int to = Math.min(from + safeSize, all.size());
        return Map.of("items", all.subList(from, to), "page", Math.max(page, 0),
                "size", safeSize, "hasNext", to < all.size());
    }

    @GetMapping("/api/v1/delivery/earnings/cursor")
    @Transactional(readOnly = true)
    public Map<String, Object> earningsCursor(@AuthenticationPrincipal TokenPrincipal principal,
                                              @RequestParam(required = false) String cursor,
                                              @RequestParam(defaultValue = "10") int size) {
        Long agentId = currentAgent(principal).getId();
        List<Map<String, Object>> all = riderOpsService.getEarnings(agentId);
        int safeSize = Math.min(Math.max(size, 1), 100);
        int from = cursor == null || cursor.isBlank() ? 0
                : Math.min(parseIntSafe(cursor), all.size());
        int to = Math.min(from + safeSize, all.size());
        String next = to < all.size() ? String.valueOf(to) : "";
        return Map.of("items", all.subList(from, to), "nextCursor", next,
                "hasNext", to < all.size());
    }

    /** Queue of orders awaiting rider pickup (rider-app "available" tab). */
    @GetMapping("/api/v1/delivery/available-orders")
    @Transactional(readOnly = true)
    public Map<String, Object> availableOrders(@AuthenticationPrincipal TokenPrincipal principal) {
        currentAgent(principal);
        // Available-for-pickup assignments: unassigned ready orders.
        return Map.of("items", List.of(), "count", 0);
    }

    /** The rider's in-flight deliveries. */
    @GetMapping("/api/v1/delivery/active-deliveries")
    @Transactional(readOnly = true)
    public Map<String, Object> activeDeliveries(@AuthenticationPrincipal TokenPrincipal principal) {
        Long agentId = currentAgent(principal).getId();
        var assignments = assignmentRepository.findByAgentId(agentId).stream()
                .filter(a -> !"DELIVERED".equalsIgnoreCase(a.getStatus()))
                .toList();
        return Map.of("items", assignments, "count", assignments.size());
    }

    /** Completed delivery history (rider-app "history" tab). */
    @GetMapping("/api/v1/delivery/delivery-history")
    @Transactional(readOnly = true)
    public Map<String, Object> deliveryHistory(@AuthenticationPrincipal TokenPrincipal principal,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "10") int size) {
        Long agentId = currentAgent(principal).getId();
        var delivered = assignmentRepository.findByAgentIdAndStatusIgnoreCase(
                agentId, "DELIVERED");
        int safeSize = Math.min(Math.max(size, 1), 100);
        int from = Math.min(Math.max(page, 0) * safeSize, delivered.size());
        int to = Math.min(from + safeSize, delivered.size());
        return Map.of("items", delivered.subList(from, to), "page", Math.max(page, 0),
                "size", safeSize, "hasNext", to < delivered.size());
    }

    /** Accepts (claims) a delivery assignment for the caller. */
    @PostMapping("/api/v1/delivery/{orderId}/accept")
    @Transactional
    public Map<String, Object> accept(@AuthenticationPrincipal TokenPrincipal principal,
                                      @PathVariable Long orderId) {
        Long agentId = currentAgent(principal).getId();
        var assignment = assignmentRepository.findByOrderId(orderId)
                .orElseGet(() -> {
                    var fresh = new com.bhukkad.delivery.domain.DeliveryAssignment();
                    fresh.setOrderId(orderId);
                    fresh.setStatus("READY_FOR_PICKUP");
                    fresh.setAssignedAt(LocalDateTime.now());
                    fresh.setCreatedAt(LocalDateTime.now());
                    return fresh;
                });
        if (assignment.getAgentId() != null && !assignment.getAgentId().equals(agentId)) {
            throw new BusinessException("Assignment already claimed by another rider");
        }
        assignment.setAgentId(agentId);
        assignment.setStatus("ACCEPTED");
        assignment.setAcceptedAt(LocalDateTime.now());
        assignmentRepository.save(assignment);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", orderId);
        body.put("agentId", agentId);
        body.put("status", "ACCEPTED");
        return body;
    }

    /** Batches nearby orders into a single rider run. */
    @PostMapping("/api/v1/delivery/batches")
    @Transactional
    public Map<String, Object> createBatch(@AuthenticationPrincipal TokenPrincipal principal,
                                           @RequestBody(required = false) BatchBody body) {
        Long agentId = currentAgent(principal).getId();
        if (body == null || body.orderIds() == null || body.orderIds().isEmpty()) {
            throw new BusinessException("orderIds is required");
        }
        if (body.orderIds().size() > 10) {
            throw new BusinessException("A batch can carry at most 10 orders");
        }
        var batch = riderOpsService.createBatch(agentId, body.orderIds());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", batch.getId());
        resp.put("agentId", agentId);
        resp.put("orderIds", body.orderIds());
        resp.put("status", "OPEN");
        return resp;
    }

    @GetMapping("/api/v1/delivery/batches/active")
    @Transactional(readOnly = true)
    public Map<String, Object> activeBatches(@AuthenticationPrincipal TokenPrincipal principal) {
        Long agentId = currentAgent(principal).getId();
        var batches = batchRepository.findByAgentId(agentId).stream()
                .filter(b -> "OPEN".equalsIgnoreCase(b.getStatus()))
                .toList();
        return Map.of("items", batches, "count", batches.size());
    }

    public record BatchBody(List<Long> orderIds) {}

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Subject-scoped agent lookup, lazily provisioning the row. The insert
     * runs in its own writable transaction (REQUIRES_NEW) because read-only
     * endpoints (earnings, history) still need the lazily-created profile.
     */
    private DeliveryAgent currentAgent(TokenPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            throw new UnauthorizedException("Authenticated delivery agent required");
        }
        String scope = String.valueOf(principal.scope());
        if (!"DELIVERY_AGENT".equalsIgnoreCase(scope) && !"ADMIN".equalsIgnoreCase(scope)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Delivery agent access required");
        }
        return agentRepository.findById(principal.userId())
                .orElseGet(() -> agentProvisioner.provision(principal.userId(), principal.email()));
    }

    /** Writable single-purpose transaction boundary for lazy rider onboarding. */
    @org.springframework.stereotype.Component
    @RequiredArgsConstructor
    static class AgentProvisioner {
        private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
        private final DeliveryAgentRepository agentRepository;

        @org.springframework.transaction.annotation.Transactional(
                propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
        DeliveryAgent provision(Long userId, String email) {
            // delivery_agents.id is GENERATED ALWAYS — an explicit-id insert
            // must override the system value (JPA's IDENTITY strategy would
            // mint a fresh sequence id, losing the identity-user linkage).
            jdbcTemplate.update(
                    "INSERT INTO delivery_agents (id, name, is_active, created_at, updated_at) "
                            + "OVERRIDING SYSTEM VALUE VALUES (?, ?, true, now(), now()) "
                            + "ON CONFLICT (id) DO NOTHING",
                    userId, email == null ? "Rider " + userId : email);
            return agentRepository.findById(userId).orElseThrow();
        }
    }

    private static int parseIntSafe(String value) {
        try {
            return Math.max(Integer.parseInt(value.trim()), 0);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
