package com.bhukkad.tenant;

import com.bhukkad.dto.request.TenantRequest;
import com.bhukkad.dto.response.TenantResponse;
import com.bhukkad.entity.Tenant;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * White-label B2B tenant management with domain-based isolation (White-label Solution).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TenantService {

    private final TenantRepository tenantRepository;

    public List<TenantResponse> listAll() {
        return tenantRepository.findAll().stream().map(this::toResponse).toList();
    }

    @Transactional
    public TenantResponse create(TenantRequest request) {
        String domain = request.getDomain().trim().toLowerCase();
        if (tenantRepository.existsByDomainIgnoreCase(domain)) {
            throw new BusinessException("Tenant with domain " + domain + " already exists");
        }
        Tenant tenant = new Tenant();
        applyRequest(tenant, request);
        return toResponse(tenantRepository.save(tenant));
    }

    @Transactional
    public TenantResponse update(Long id, TenantRequest request) {
        Tenant tenant = findOrThrow(id);
        if (request.getDomain() != null && !request.getDomain().equalsIgnoreCase(tenant.getDomain())
                && tenantRepository.existsByDomainIgnoreCase(request.getDomain())) {
            throw new BusinessException("Tenant with domain " + request.getDomain() + " already exists");
        }
        applyRequest(tenant, request);
        return toResponse(tenantRepository.save(tenant));
    }

    @Transactional
    public void deactivate(Long id) {
        Tenant tenant = findOrThrow(id);
        tenant.setIsActive(false);
        tenantRepository.save(tenant);
    }

    /**
     * Public storefront config lookup for a tenant domain. Only active tenants resolve.
     */
    public TenantResponse getByDomain(String domain) {
        Tenant tenant = tenantRepository.findByDomainIgnoreCase(domain)
                .filter(Tenant::getIsActive)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found for domain " + domain));
        return toResponse(tenant);
    }

    private Tenant findOrThrow(Long id) {
        return tenantRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found"));
    }

    private void applyRequest(Tenant tenant, TenantRequest request) {
        if (request.getName() != null) tenant.setName(request.getName().trim());
        if (request.getDomain() != null) tenant.setDomain(request.getDomain().trim().toLowerCase());
        if (request.getBrandName() != null) tenant.setBrandName(request.getBrandName());
        if (request.getLogoUrl() != null) tenant.setLogoUrl(request.getLogoUrl());
        if (request.getThemeColor() != null) tenant.setThemeColor(request.getThemeColor());
        if (request.getCurrency() != null) tenant.setCurrency(request.getCurrency().trim().toUpperCase());
        if (request.getIsActive() != null) tenant.setIsActive(request.getIsActive());
    }

    private TenantResponse toResponse(Tenant tenant) {
        return TenantResponse.builder()
                .id(tenant.getId())
                .name(tenant.getName())
                .domain(tenant.getDomain())
                .brandName(tenant.getBrandName())
                .logoUrl(tenant.getLogoUrl())
                .themeColor(tenant.getThemeColor())
                .currency(tenant.getCurrency())
                .isActive(tenant.getIsActive())
                .build();
    }
}
