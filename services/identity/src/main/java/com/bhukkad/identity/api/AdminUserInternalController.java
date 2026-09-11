package com.bhukkad.identity.api;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.common.security.JwtRevocationService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Platform-admin user operations (service-internal surface): verification,
 * activation and GDPR erasure over the identity {@code users} table. Reachable
 * only on the service mesh — the gateway never routes {@code /internal/**},
 * and service-to-service calls authenticate with the shared service token.
 */
@RestController
@RequestMapping("/api/v1/internal/admin")
@RequiredArgsConstructor
public class AdminUserInternalController {

    private final JdbcTemplate jdbcTemplate;
    /** P1: deactivation must invalidate already-issued access tokens immediately. */
    private final JwtRevocationService revocationService;

    /** Paged user registry for the admin console (safe columns only). */
    @GetMapping("/users")
    @Transactional(readOnly = true)
    public Map<String, Object> users(@org.springframework.web.bind.annotation.RequestParam(
            defaultValue = "0") int page,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, role, active, email_verified, created_at FROM users "
                        + "ORDER BY id LIMIT ? OFFSET ?",
                safeSize, safePage * safeSize);
        Integer total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", rows);
        body.put("page", safePage);
        body.put("size", safeSize);
        body.put("totalElements", total == null ? 0 : total);
        body.put("hasNext", (long) (safePage + 1) * safeSize < (total == null ? 0 : total));
        return body;
    }

    @GetMapping("/users/{userId}")
    @Transactional(readOnly = true)
    public Map<String, Object> user(@PathVariable Long userId) {
        try {
            return jdbcTemplate.queryForMap(
                    "SELECT id, role, active, email_verified FROM users WHERE id = ?", userId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResourceNotFoundException("User not found: " + userId);
        }
    }

    @PutMapping("/users/{userId}/verify")
    @Transactional
    public Map<String, Object> verify(@PathVariable Long userId,
                                      @RequestParam(required = false) String expectedRole) {
        Map<String, Object> user = loadUser(userId);
        String role = String.valueOf(user.get("role"));
        if (expectedRole != null && !expectedRole.isBlank() && !expectedRole.equals(role)) {
            throw new BusinessException("User " + userId + " is not a " + expectedRole
                    + " (role=" + role + ")");
        }
        jdbcTemplate.update("UPDATE users SET email_verified = TRUE, "
                + "profile_completed = TRUE, updated_at = NOW() WHERE id = ?", userId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("role", role);
        body.put("verified", true);
        return body;
    }

    @PutMapping("/users/{userId}/activate")
    @Transactional
    public Map<String, Object> activate(@PathVariable Long userId) {
        loadUser(userId);
        jdbcTemplate.update("UPDATE users SET active = TRUE, updated_at = NOW() WHERE id = ?", userId);
        return Map.of("userId", userId, "active", true);
    }

    @PutMapping("/users/{userId}/deactivate")
    @Transactional
    public Map<String, Object> deactivate(@PathVariable Long userId) {
        loadUser(userId);
        jdbcTemplate.update("UPDATE users SET active = FALSE, updated_at = NOW() WHERE id = ?", userId);
        revocationService.revokeTokensIssuedBefore(userId);
        return Map.of("userId", userId, "active", false);
    }

    /** GDPR erasure: anonymise contact fields + deactivate the account. */
    @PostMapping("/users/{userId}/erase")
    @Transactional
    public Map<String, Object> erase(@PathVariable Long userId) {
        loadUser(userId);
        jdbcTemplate.update(
                "UPDATE users SET active = FALSE, updated_at = NOW() WHERE id = ?", userId);
        // Contact data lives on the id-keyed profile tables (customers rows are
        // user-id-keyed; owners/agents share the same id space via registration).
        // Positional parameters — the field is a plain JdbcTemplate, so the
        // :named style previously threw BadSqlGrammarException and surfaced
        // as a 500 on the DPDP erasure path.
        jdbcTemplate.update(
                "UPDATE customers SET email = CONCAT('deleted', ?, '@bhukkad.invalid'), "
                        + "full_name = 'Deleted User', phone_number = CONCAT('X', ABS((? * 7919) % 1000000000)), "
                        + "is_active = FALSE WHERE id = ?",
                "-del-" + userId, userId, userId);
        jdbcTemplate.update(
                "UPDATE restaurant_owners SET email = CONCAT('deleted', ?, '@bhukkad.invalid'), "
                        + "full_name = 'Deleted User', phone_number = CONCAT('X', ABS((? * 7919) % 1000000000)), "
                        + "verified = FALSE WHERE id = ?",
                "-del-" + userId, userId, userId);
        jdbcTemplate.update(
                "UPDATE delivery_agents SET email = CONCAT('deleted', ?, '@bhukkad.invalid'), "
                        + "full_name = 'Deleted User', phone_number = CONCAT('X', ABS((? * 7919) % 1000000000)), "
                        + "verified = FALSE WHERE id = ?",
                "-del-" + userId, userId, userId);
        revocationService.revokeTokensIssuedBefore(userId);
        return Map.of("userId", userId, "erased", true);
    }

    private Map<String, Object> loadUser(Long userId) {
        try {
            return jdbcTemplate.queryForMap("SELECT id, role, active FROM users WHERE id = ?", userId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResourceNotFoundException("User not found: " + userId);
        }
    }
}
