package com.bhukkad.identity.domain.service.impl;

import com.bhukkad.identity.domain.entity.Tenant;
import com.bhukkad.identity.domain.repository.TenantRepository;

import com.bhukkad.common.error.DuplicateRequestException;
import com.bhukkad.common.scan.AllowFullScan;
import com.bhukkad.identity.domain.entity.Tenant;
import com.bhukkad.identity.domain.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * White-label tenant management (Priority 1).
 */
@Service
@RequiredArgsConstructor
public class TenantService {

    private final TenantRepository tenantRepository;

    @Transactional
    public Tenant create(String name, String domain) {
        if (tenantRepository.findByDomain(domain).isPresent()) {
            throw new DuplicateRequestException("Tenant domain already registered: " + domain);
        }
        Tenant tenant = new Tenant();
        tenant.setName(name);
        tenant.setDomain(domain);
        return tenantRepository.save(tenant);
    }

    @Transactional(readOnly = true)
    public Tenant byDomain(String domain) {
        return tenantRepository.findByDomain(domain).orElse(null);
    }

    /** All tenants, newest first (admin console listing). */
    @Transactional(readOnly = true)
    @AllowFullScan(reason = "G-6 reviewed: tenants are a small bounded B2B reference table (rows grow with signed-up tenants, not traffic)")
    public java.util.List<Tenant> list() {
        return tenantRepository.findAllByOrderByIdDesc();
    }
}