package com.bhukkad.identity.api.controller;

import com.bhukkad.identity.domain.entity.Tenant;
import com.bhukkad.identity.domain.service.impl.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * White-label tenant endpoints (port of monolith {@code TenantController}).
 * <p>
 * Tenant lifecycle (onboarding) is an admin operation owned by the identity
 * service. The admin service has no TenantController of its own, so the
 * onboarding wizard endpoints live here.
 */
@RestController
@RequestMapping("/api/v1/tenants")
@RequiredArgsConstructor
public class TenantController {

    private final TenantService tenantService;

    private final StringRedisTemplate stringRedisTemplate;

    /** Redis field key for a per-tenant feature-flag override. */
    private static final String TENANT_FEATURE_OVERRIDES = "bhukkad:tenant:features";

    private static String featureField(Long tenantId, String flag) {
        return tenantId + ":" + flag;
    }

    /** Tenant list for the B2B admin console. */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public java.util.List<Tenant> list() {
        return tenantService.list();
    }

    /**
     * Tenant provisioning is a B2B/admin operation, never customer-reachable.
     * Accepts the JSON body form (ops console) and the query-param form.
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Tenant create(@org.springframework.web.bind.annotation.RequestBody(
            required = false) com.bhukkad.identity.api.dto.request.TenantRequest request,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String domain) {
        String effectiveName = request != null && request.getName() != null
                ? request.getName() : name;
        String effectiveDomain = request != null && request.getDomain() != null
                ? request.getDomain() : domain;
        if (effectiveName == null || effectiveName.isBlank()) {
            throw new com.bhukkad.common.error.BusinessException("Tenant name is required");
        }
        if (effectiveDomain == null || effectiveDomain.isBlank()) {
            throw new com.bhukkad.common.error.BusinessException("Domain is required");
        }
        return tenantService.create(effectiveName.trim(), effectiveDomain.trim());
    }

    @GetMapping("/by-domain")
    public ResponseEntity<Tenant> byDomain(@RequestParam String domain) {
        // 404 instead of a 200-with-empty-body for unknown domains.
        return ResponseEntity.ofNullable(tenantService.byDomain(domain));
    }

    /**
     * Onboarding wizard: domain verification step (placeholder).
     * Confirms the given domain is registered to this tenant. Real verification
     * (DNS TXT/HTTP token) is implemented by the ops console against this
     * endpoint contract.
     */
    @GetMapping("/{tenantId}/verify-domain")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> verifyDomain(@PathVariable Long tenantId,
                                            @RequestParam String domain) {
        Tenant tenant = tenantService.byDomain(domain);
        boolean verified = tenant != null && tenant.getId().equals(tenantId);
        return Map.of("tenantId", tenantId, "domain", domain, "verified", verified);
    }

    /**
     * Onboarding wizard: per-tenant feature-flag override. Overrides are stored
     * in Redis and shadow the global flag value for this tenant only.
     */
    @GetMapping("/{tenantId}/features/{flag}")
    @PreAuthorize("hasRole('ADMIN')")
    public Boolean featureFlag(@PathVariable Long tenantId, @PathVariable String flag) {
        String value = (String) stringRedisTemplate.opsForHash()
                .get(TENANT_FEATURE_OVERRIDES, featureField(tenantId, flag));
        return "true".equals(value);
    }

    @PutMapping("/{tenantId}/features/{flag}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> setFeatureFlag(@PathVariable Long tenantId,
                                               @PathVariable String flag,
                                               @RequestParam Boolean enabled) {
        stringRedisTemplate.opsForHash()
                .put(TENANT_FEATURE_OVERRIDES, featureField(tenantId, flag), String.valueOf(enabled));
        return ResponseEntity.noContent().build();
    }
}
