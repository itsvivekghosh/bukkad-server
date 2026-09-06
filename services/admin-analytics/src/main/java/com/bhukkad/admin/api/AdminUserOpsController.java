package com.bhukkad.admin.api;

import com.bhukkad.admin.domain.AuditEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Platform-admin user lifecycle (monolith parity): owner/agent verification,
 * account activation, city registry and promotion campaigns/banners. ADMIN
 * gated; user mutations run through identity-owned tables via parameterised
 * SQL and are audit-logged through the shared {@code audit_events} table.
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserOpsController {

    private final JdbcTemplate jdbcTemplate;
    private final AuditEventRepository auditEventRepository;
    private final com.bhukkad.admin.audit.AuditService auditService;

    // ------------------------------------------------------------------
    // Verification + activation
    // ------------------------------------------------------------------

    /** Marks a restaurant-owner account verified. */
    @PutMapping("/owners/{userId}/verify")
    @Transactional
    public Map<String, Object> verifyOwner(@PathVariable Long userId) {
        return verifyAccount(userId, "RESTAURANT_OWNER");
    }

    /** Marks a delivery-agent account verified. */
    @PutMapping("/agents/{userId}/verify")
    @Transactional
    public Map<String, Object> verifyAgent(@PathVariable Long userId) {
        return verifyAccount(userId, "DELIVERY_AGENT");
    }

    @PutMapping("/users/{userId}/activate")
    @Transactional
    public Map<String, Object> activate(@PathVariable Long userId) {
        requireUser(userId);
        jdbcTemplate.update("UPDATE users SET active = TRUE, updated_at = NOW() WHERE id = ?", userId);
        auditService.record("USER_ACTIVATED", "USER", String.valueOf(userId), null, null);
        return Map.of("userId", userId, "active", true, "message", "Account activated");
    }

    @PutMapping("/users/{userId}/deactivate")
    @Transactional
    public Map<String, Object> deactivate(@PathVariable Long userId) {
        requireUser(userId);
        jdbcTemplate.update("UPDATE users SET active = FALSE, updated_at = NOW() WHERE id = ?", userId);
        auditService.record("USER_DEACTIVATED", "USER", String.valueOf(userId), null, null);
        return Map.of("userId", userId, "active", false, "message", "Account deactivated");
    }

    /** GDPR erasure entry point for the admin compliance console. */
    @PostMapping("/users/{userId}/erase")
    @Transactional
    public Map<String, Object> erase(@PathVariable Long userId) {
        requireUser(userId);
        String suffix = "-del-" + userId;
        jdbcTemplate.update(
                "UPDATE users SET active = FALSE, email = CONCAT('deleted', :suffix, '@bhukkad.invalid'), "
                        + "full_name = 'Deleted User', phone_number = CONCAT('X', ABS((:id * 7919) % 1000000000)), "
                        + "updated_at = NOW() WHERE id = :id",
                Map.of("suffix", suffix, "id", userId));
        auditService.record("USER_ERASED", "USER", String.valueOf(userId), null, null);
        return Map.of("userId", userId, "erased", true, "message", "User data erased");
    }

    // ------------------------------------------------------------------
    // City registry
    // ------------------------------------------------------------------

    @GetMapping("/cities")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> cities() {
        // City registry lives in the delivery DB (city_configs); read through
        // a lightweight projection so admin ops needs no cross-domain entities.
        return jdbcTemplate.queryForList(
                "SELECT id, city_name, currency, timezone, created_at FROM city_configs ORDER BY city_name");
    }

    /** Adds a city to the delivery footprint (dev build accepts named cities). */
    @PostMapping("/cities")
    @Transactional
    public ResponseEntity<Map<String, Object>> createCity(
            @RequestBody(required = false) Map<String, Object> body) {
        String name = body == null ? null : (String) body.get("name");
        if (name == null || name.isBlank()) {
            throw new com.bhukkad.common.error.BusinessException("name is required");
        }
        jdbcTemplate.update(
                "INSERT INTO city_configs (city_name, currency, timezone, created_at) "
                        + "VALUES (?, 'INR', 'Asia/Kolkata', NOW())",
                name.trim());
        Map<String, Object> created = new LinkedHashMap<>();
        created.put("name", name.trim());
        created.put("currency", "INR");
        created.put("timezone", "Asia/Kolkata");
        return ResponseEntity.status(201).body(created);
    }

    // ------------------------------------------------------------------
    // Promotions (campaigns + banners)
    // ------------------------------------------------------------------

    @GetMapping("/promotions/campaigns")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> campaigns() {
        return jdbcTemplate.queryForList(
                "SELECT id, name, campaign_type, discount_percent, is_active, starts_at, ends_at "
                        + "FROM promotion_campaigns ORDER BY id DESC");
    }

    @PostMapping("/promotions/campaigns")
    @Transactional
    public ResponseEntity<Map<String, Object>> createCampaign(
            @RequestBody(required = false) Map<String, Object> body) {
        String name = body == null ? null : (String) body.get("name");
        if (name == null || name.isBlank()) {
            throw new com.bhukkad.common.error.BusinessException("name is required");
        }
        String campaignType = String.valueOf(body.getOrDefault("campaignType", "DISCOUNT"));
        Double discountPercent = body.get("discountPercent") == null ? 10.0
                : Double.valueOf(String.valueOf(body.get("discountPercent")));
        jdbcTemplate.update(
                "INSERT INTO promotion_campaigns (name, campaign_type, discount_percent, "
                        + "starts_at, ends_at, is_active) VALUES (?, ?, ?, NOW(), NOW() + INTERVAL '30 days', TRUE)",
                name.trim(), campaignType, discountPercent);
        Map<String, Object> created = new LinkedHashMap<>();
        created.put("name", name.trim());
        created.put("campaignType", campaignType);
        created.put("discountPercent", discountPercent);
        created.put("isActive", true);
        return ResponseEntity.status(201).body(created);
    }

    @GetMapping("/promotions/banners")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> banners() {
        return jdbcTemplate.queryForList(
                "SELECT id, title, image_url, action_target, display_order, is_active "
                        + "FROM promo_banners ORDER BY display_order, id");
    }

    @PostMapping("/promotions/banners")
    @Transactional
    public ResponseEntity<Map<String, Object>> createBanner(
            @RequestBody(required = false) Map<String, Object> body) {
        String title = body == null ? null : (String) body.get("title");
        if (title == null || title.isBlank()) {
            throw new com.bhukkad.common.error.BusinessException("title is required");
        }
        String imageUrl = body.get("imageUrl") == null ? "" : String.valueOf(body.get("imageUrl"));
        String targetUrl = body.get("targetUrl") == null ? "" : String.valueOf(body.get("targetUrl"));
        Object position = body.getOrDefault("position", 0);
        jdbcTemplate.update(
                "INSERT INTO promo_banners (title, image_url, action_target, display_order, is_active) "
                        + "VALUES (?, ?, ?, ?, TRUE)",
                title.trim(), imageUrl, targetUrl,
                ((Number) position).intValue());
        Map<String, Object> created = new LinkedHashMap<>();
        created.put("title", title.trim());
        created.put("imageUrl", imageUrl);
        created.put("targetUrl", targetUrl);
        created.put("position", position);
        created.put("isActive", true);
        return ResponseEntity.status(201).body(created);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void requireUser(Long userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, userId);
        if (count == null || count == 0) {
            throw new com.bhukkad.common.error.ResourceNotFoundException("User not found: " + userId);
        }
    }

    private Map<String, Object> verifyAccount(Long userId, String expectedRole) {
        Map<String, Object> user;
        try {
            user = jdbcTemplate.queryForMap(
                    "SELECT id, role, email_verified FROM users WHERE id = ?", userId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new com.bhukkad.common.error.ResourceNotFoundException("User not found: " + userId);
        }
        String role = String.valueOf(user.get("role"));
        if (!expectedRole.equals(role)) {
            throw new com.bhukkad.common.error.BusinessException(
                    "User " + userId + " is not a " + expectedRole + " (role=" + role + ")");
        }
        jdbcTemplate.update(
                "UPDATE users SET email_verified = TRUE, profile_completed = TRUE, "
                        + "updated_at = NOW() WHERE id = ?", userId);
        auditService.record("ACCOUNT_VERIFIED", "USER", String.valueOf(userId), null, null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("role", role);
        body.put("verified", true);
        body.put("message", expectedRole + " verified");
        return body;
    }
}
