package com.bhukkad.identity.service;

import com.bhukkad.common.error.DuplicateRequestException;
import com.bhukkad.identity.domain.Tenant;
import com.bhukkad.identity.domain.TenantRepository;
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
}