package com.bhukkad.restaurant.service;

import com.bhukkad.common.scan.AllowFullScan;
import com.bhukkad.restaurant.domain.Tenant;
import com.bhukkad.restaurant.domain.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * White-label B2B tenant management.
 *
 * <p>Restaurant-service port of the monolith
 * {@code com.bhukkad.tenant.TenantService}. Operates against a service-local
 * {@link Tenant} entity that follows the restaurants schema. The
 * monolith keeps a working copy with the full DTO-based contract until the
 * gateway flips.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TenantService {

    private final TenantRepository tenantRepository;

    @AllowFullScan(reason = "G-6 reviewed: tenants are a small bounded B2B reference table (rows grow with signed-up tenants, not traffic)")
    public List<Tenant> listAll() {
        return tenantRepository.findAll();
    }

    @Transactional
    public Tenant create(Tenant tenant) {
        if (tenant.getDomain() == null) {
            throw new IllegalArgumentException("Tenant domain is required");
        }
        String normalized = tenant.getDomain().trim().toLowerCase();
        if (tenantRepository.existsByDomainIgnoreCase(normalized)) {
            throw new IllegalArgumentException(
                    "Tenant with domain " + normalized + " already exists");
        }
        tenant.setDomain(normalized);
        return tenantRepository.save(tenant);
    }

    @Transactional
    public Tenant update(Long id, Tenant patch) {
        Tenant tenant = tenantRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + id));
        if (patch.getName() != null) tenant.setName(patch.getName());
        if (patch.getDomain() != null) tenant.setDomain(patch.getDomain().trim().toLowerCase());
        if (patch.getBrandName() != null) tenant.setBrandName(patch.getBrandName());
        if (patch.getLogoUrl() != null) tenant.setLogoUrl(patch.getLogoUrl());
        if (patch.getThemeColor() != null) tenant.setThemeColor(patch.getThemeColor());
        if (patch.getCurrency() != null) tenant.setCurrency(patch.getCurrency().trim().toUpperCase());
        if (patch.getIsActive() != null) tenant.setIsActive(patch.getIsActive());
        return tenantRepository.save(tenant);
    }

    @Transactional
    public void deactivate(Long id) {
        Tenant tenant = tenantRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + id));
        tenant.setIsActive(false);
        tenantRepository.save(tenant);
    }
}