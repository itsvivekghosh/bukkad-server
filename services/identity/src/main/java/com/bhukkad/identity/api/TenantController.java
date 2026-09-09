package com.bhukkad.identity.api;

import com.bhukkad.identity.domain.Tenant;
import com.bhukkad.identity.service.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * White-label tenant endpoints (port of monolith {@code TenantController}).
 */
@RestController
@RequestMapping("/api/v1/tenants")
@RequiredArgsConstructor
public class TenantController {

    private final TenantService tenantService;

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
            required = false) com.bhukkad.identity.api.TenantRequest request,
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
}