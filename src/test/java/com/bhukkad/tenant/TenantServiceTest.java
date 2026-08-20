package com.bhukkad.tenant;

import com.bhukkad.dto.request.TenantRequest;
import com.bhukkad.entity.Tenant;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.TenantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TenantServiceTest {

    @Mock
    private TenantRepository tenantRepository;

    @InjectMocks
    private TenantService service;

    private TenantRequest validRequest() {
        TenantRequest request = new TenantRequest();
        request.setName("Acme Corp");
        request.setDomain("acme.example.com");
        request.setBrandName("Acme Eats");
        request.setCurrency("INR");
        return request;
    }

    @Test
    void create_savesTenantWithNormalizedDomain() {
        when(tenantRepository.existsByDomainIgnoreCase("acme.example.com")).thenReturn(false);
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        TenantRequest request = validRequest();
        request.setDomain("Acme.Example.com");

        var response = service.create(request);

        assertEquals("acme.example.com", response.getDomain());
        assertTrue(response.getIsActive());
        verify(tenantRepository).save(any(Tenant.class));
    }

    @Test
    void create_duplicateDomain_throwsBusinessException() {
        when(tenantRepository.existsByDomainIgnoreCase("acme.example.com")).thenReturn(true);

        assertThrows(BusinessException.class, () -> service.create(validRequest()));
        verify(tenantRepository, never()).save(any());
    }

    @Test
    void getByDomain_activeTenant_returnsConfig() {
        Tenant tenant = new Tenant();
        tenant.setId(1L);
        tenant.setName("Acme Corp");
        tenant.setDomain("acme.example.com");
        tenant.setBrandName("Acme Eats");
        tenant.setIsActive(true);

        when(tenantRepository.findByDomainIgnoreCase("acme.example.com"))
                .thenReturn(Optional.of(tenant));

        var response = service.getByDomain("acme.example.com");

        assertEquals("acme.example.com", response.getDomain());
        assertEquals("Acme Eats", response.getBrandName());
    }

    @Test
    void getByDomain_inactiveTenant_isHidden() {
        Tenant tenant = new Tenant();
        tenant.setId(1L);
        tenant.setDomain("acme.example.com");
        tenant.setIsActive(false);

        when(tenantRepository.findByDomainIgnoreCase("acme.example.com"))
                .thenReturn(Optional.of(tenant));

        assertThrows(ResourceNotFoundException.class, () -> service.getByDomain("acme.example.com"));
    }

    @Test
    void getByDomain_unknownDomain_throwsNotFound() {
        when(tenantRepository.findByDomainIgnoreCase("nope.example.com"))
                .thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getByDomain("nope.example.com"));
    }

    @Test
    void update_toDuplicateDomain_throwsBusinessException() {
        Tenant existing = new Tenant();
        existing.setId(1L);
        existing.setName("Acme Corp");
        existing.setDomain("acme.example.com");

        when(tenantRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(tenantRepository.existsByDomainIgnoreCase("other.example.com")).thenReturn(true);

        TenantRequest request = validRequest();
        request.setDomain("other.example.com");

        assertThrows(BusinessException.class, () -> service.update(1L, request));
    }

    @Test
    void deactivate_marksInactive() {
        Tenant existing = new Tenant();
        existing.setId(1L);
        existing.setDomain("acme.example.com");
        existing.setIsActive(true);

        when(tenantRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        service.deactivate(1L);

        assertFalse(existing.getIsActive());
    }

    @Test
    void deactivate_unknownId_throwsNotFound() {
        when(tenantRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.deactivate(1L));
    }
}
