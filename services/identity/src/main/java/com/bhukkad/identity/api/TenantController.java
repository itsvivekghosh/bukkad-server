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

    /** Tenant provisioning is a B2B/admin operation, never customer-reachable. */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Tenant create(@RequestParam String name, @RequestParam String domain) {
        return tenantService.create(name, domain);
    }

    @GetMapping("/by-domain")
    public ResponseEntity<Tenant> byDomain(@RequestParam String domain) {
        // 404 instead of a 200-with-empty-body for unknown domains.
        return ResponseEntity.ofNullable(tenantService.byDomain(domain));
    }
}