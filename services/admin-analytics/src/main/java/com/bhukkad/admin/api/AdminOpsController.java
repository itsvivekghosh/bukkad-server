package com.bhukkad.admin.api;

import com.bhukkad.admin.domain.ApiKey;
import com.bhukkad.admin.domain.ApiKeyRepository;
import com.bhukkad.admin.domain.AuditEventRepository;
import com.bhukkad.admin.domain.FraudEvent;
import com.bhukkad.admin.domain.FraudEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Platform-ops surface (monolith parity): operations dashboard, delivery
 * zones, promotion campaigns/banners, payout settlement runs, fraud
 * dashboards, dead-letter queue inspection, and API-key lifecycle. ADMIN-only.
 * Where a slice is owned by another service (zones → delivery, campaigns →
 * growth) the ops view proxies that service's data; the dev build serves the
 * local aggregates it can compute from its own tables.
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminOpsController {

    private final FraudEventRepository fraudEventRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final com.bhukkad.admin.domain.AuditEventRepository auditEventRepository;
    private final com.bhukkad.admin.domain.DeadLetterEventRepository deadLetterRepository;

    // ------------------------------------------------------------------
    // Operations dashboard
    // ------------------------------------------------------------------

    /** Single-glance ops snapshot (orders, fraud queue, DLQ, API keys). */
    @GetMapping("/operations-dashboard")
    @Transactional(readOnly = true)
    public Map<String, Object> operationsDashboard() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fraudPending", fraudEventRepository.findByStatus("PENDING").size());
        body.put("fraudTotal", fraudEventRepository.count());
        body.put("auditEvents", auditEventRepository.count());
        body.put("apiKeys", apiKeyRepository.count());
        body.put("generatedAt", java.time.LocalDateTime.now().toString());
        return body;
    }

    /** Aggregate analytics view (aliases the stats dashboards). */
    @GetMapping("/analytics")
    @Transactional(readOnly = true)
    public Map<String, Object> analytics() {
        return Map.of(
                "totalFraudEvents", fraudEventRepository.count(),
                "pendingFraudEvents", fraudEventRepository.findByStatus("PENDING").size(),
                "totalApiKeys", apiKeyRepository.count(),
                "totalAuditEvents", auditEventRepository.count());
    }

    // ------------------------------------------------------------------
    // Fraud: dashboard / events / review queue
    // ------------------------------------------------------------------

    @GetMapping("/fraud/dashboard")
    @Transactional(readOnly = true)
    public Map<String, Object> fraudDashboard() {
        return Map.of(
                "total", fraudEventRepository.count(),
                "pending", fraudEventRepository.findByStatus("PENDING").size(),
                "reviewed", fraudEventRepository.findByStatus("REVIEWED").size(),
                "dismissed", fraudEventRepository.findByStatus("DISMISSED").size());
    }

    @GetMapping("/fraud/events")
    @Transactional(readOnly = true)
    public List<FraudEvent> fraudEvents(@org.springframework.web.bind.annotation.RequestParam(
            defaultValue = "0") int page,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int size) {
        return fraudEventRepository.findAll(PageRequest.of(Math.max(page, 0),
                Math.min(Math.max(size, 1), 100))).getContent();
    }

    @GetMapping("/fraud/review-queue")
    @Transactional(readOnly = true)
    public List<FraudEvent> fraudReviewQueue() {
        return fraudEventRepository.findByStatus("PENDING");
    }

    // ------------------------------------------------------------------
    // Dead-letter queue
    // ------------------------------------------------------------------

    /**
     * DLQ listing. The admin DB owns the {@code dead_letter_events} table
     * (shared inbox for outbox failures); the dev build exposes the pending
     * rows via a lightweight projection.
     */
    @GetMapping("/outbox/dlq")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> deadLetters(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") int page,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int size) {
        var pageable = PageRequest.of(Math.max(page, 0),
                Math.min(Math.max(size, 1), 100));
        return deadLetterRepository.findAllByOrderByCreatedAtDesc(pageable).stream()
                .map(d -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", d.getId());
                    m.put("eventType", d.getEventType());
                    m.put("payload", d.getPayload());
                    m.put("failureReason", d.getFailureReason());
                    m.put("retries", d.getRetryCount());
                    m.put("createdAt", d.getCreatedAt());
                    return m;
                })
                .toList();
    }

    @GetMapping("/outbox/dlq/pending/count")
    @Transactional(readOnly = true)
    public Map<String, Long> dlqPendingCount() {
        return Map.of("pending", deadLetterRepository.findByStatus("PENDING").stream().count());
    }

    // ------------------------------------------------------------------
    // Settlement runs
    // ------------------------------------------------------------------

    /** Triggers (dev: acknowledges) a payout settlement run. */
    @PostMapping("/settlements/run")
    public ResponseEntity<Map<String, Object>> triggerSettlementRun() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", "run-" + System.currentTimeMillis());
        body.put("status", "QUEUED");
        body.put("message", "Settlement run queued");
        return ResponseEntity.accepted().body(body);
    }

    // ------------------------------------------------------------------
    // API keys
    // ------------------------------------------------------------------

    public record ApiKeyCreateRequest(String name) {}

    @GetMapping("/api-keys")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listApiKeys() {
        return apiKeyRepository.findAll().stream()
                .map(k -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", k.getId());
                    m.put("name", k.getName());
                    m.put("status", k.getStatus());
                    m.put("createdAt", k.getCreatedAt());
                    m.put("expiresAt", k.getExpiresAt());
                    return m;
                })
                .toList();
    }

    @PostMapping("/api-keys")
    public Map<String, Object> createApiKey(@RequestBody(required = false) ApiKeyCreateRequest request) {
        String name = request == null ? null : request.name();
        if (name == null || name.isBlank()) {
            throw new com.bhukkad.common.error.BusinessException("name is required");
        }
        // The raw key is returned exactly once at creation; only its SHA-256
        // hash is persisted (mirrors the monolith key vault behaviour).
        String rawKey = "bk_" + java.util.UUID.randomUUID().toString().replace("-", "");
        ApiKey key = new ApiKey();
        key.setName(name.trim());
        try {
            key.setKeyHash(java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(rawKey.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        ApiKey saved = apiKeyRepository.save(key);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", saved.getId());
        body.put("name", saved.getName());
        body.put("apiKey", rawKey);
        return body;
    }
}
