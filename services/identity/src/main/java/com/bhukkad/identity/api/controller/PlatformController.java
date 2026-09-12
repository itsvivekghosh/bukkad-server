package com.bhukkad.identity.api.controller;

import com.bhukkad.common.error.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Platform discovery surface (monolith parity): the list of cities with
 * active delivery and per-domain tenant resolution for white-label builds.
 * Public metadata — no PII, safe to cache.
 */
@RestController
@RequiredArgsConstructor
public class PlatformController {

    /** Cities with live delivery operations (matches the seeded zones). */
    private static final List<Map<String, Object>> ACTIVE_CITIES = List.of(
            Map.of("name", "Bangalore", "state", "Karnataka", "active", true),
            Map.of("name", "Mumbai", "state", "Maharashtra", "active", true),
            Map.of("name", "Delhi", "state", "Delhi", "active", true),
            Map.of("name", "Hyderabad", "state", "Telangana", "active", true),
            Map.of("name", "Pune", "state", "Maharashtra", "active", true));

    private final com.bhukkad.identity.domain.repository.TenantRepository tenantRepository;

    @GetMapping("/api/v1/platform/cities")
    public Map<String, Object> cities() {
        return Map.of("cities", ACTIVE_CITIES, "count", ACTIVE_CITIES.size());
    }

    @GetMapping("/api/v1/platform/tenants/{domain}")
    public Map<String, Object> tenantByDomain(@PathVariable String domain) {
        return tenantRepository.findByDomain(domain)
                .<Map<String, Object>>map(t -> Map.of(
                        "id", t.getId(),
                        "name", t.getName() == null ? "" : t.getName(),
                        "domain", t.getDomain() == null ? "" : t.getDomain(),
                        "brandName", t.getBrandName() == null ? "" : t.getBrandName()))
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + domain));
    }
}
