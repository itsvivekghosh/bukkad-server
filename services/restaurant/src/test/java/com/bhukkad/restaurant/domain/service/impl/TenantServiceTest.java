package com.bhukkad.restaurant.domain.service.impl;

import com.bhukkad.restaurant.domain.entity.Tenant;
import com.bhukkad.restaurant.domain.repository.TenantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantServiceTest {

    @Mock
    private TenantRepository tenantRepository;

    @InjectMocks
    private TenantService service;

    @Test
    void create_normalizesDomainAndSaves() {
        when(tenantRepository.existsByDomainIgnoreCase("bhukkad.in")).thenReturn(false);
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        Tenant input = new Tenant();
        input.setName("Bhukkad");
        input.setDomain("  Bhukkad.IN  ");
        input.setBrandName("Bhukkad HQ");
        input.setCurrency("INR");

        Tenant saved = service.create(input);

        ArgumentCaptor<Tenant> captor = ArgumentCaptor.forClass(Tenant.class);
        verify(tenantRepository).save(captor.capture());
        assertThat(captor.getValue().getDomain()).isEqualTo("bhukkad.in");
        assertThat(captor.getValue().getCurrency()).isEqualTo("INR");
        assertThat(saved.getBrandName()).isEqualTo("Bhukkad HQ");
    }

    @Test
    void create_rejectsDuplicateDomain() {
        when(tenantRepository.existsByDomainIgnoreCase("bhukkad.in")).thenReturn(true);
        Tenant input = new Tenant();
        input.setDomain("bhukkad.in");

        assertThatThrownBy(() -> service.create(input))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void update_modifiesOnlyProvidedFields() {
        Tenant existing = new Tenant();
        existing.setId(1L);
        existing.setName("Old");
        existing.setDomain("old.in");
        when(tenantRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        Tenant patch = new Tenant();
        patch.setName("New");
        Tenant updated = service.update(1L, patch);

        assertThat(updated.getName()).isEqualTo("New");
        assertThat(updated.getDomain()).isEqualTo("old.in");
    }

    @Test
    void update_throws_whenMissing() {
        when(tenantRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(99L, new Tenant()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deactivate_flagsInactive() {
        Tenant existing = new Tenant();
        existing.setId(1L);
        existing.setIsActive(true);
        when(tenantRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        service.deactivate(1L);

        ArgumentCaptor<Tenant> captor = ArgumentCaptor.forClass(Tenant.class);
        verify(tenantRepository).save(captor.capture());
        assertThat(captor.getValue().getIsActive()).isFalse();
    }
}