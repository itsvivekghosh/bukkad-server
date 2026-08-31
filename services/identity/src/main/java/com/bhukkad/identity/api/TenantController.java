package com.bhukkad.identity.api;

import com.bhukkad.identity.domain.Tenant;
import com.bhukkad.identity.service.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * White-label tenant endpoints (port of monolith {@code TenantController}).
 */
@RestController
@RequestMapping("/api/v1/tenants")
@RequiredArgsConstructor
public class TenantController {

    private final TenantService tenantService;

    @PostMapping
    public Tenant create(@RequestParam String name, @RequestParam String domain) {
        return tenantService.create(name, domain);
    }

    @GetMapping("/by-domain")
    public Tenant byDomain(@RequestParam String domain) {
        return tenantService.byDomain(domain);
    }
}