package com.bhukkad.restaurant.domain.service.impl;

import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.restaurant.domain.entity.PromotionCampaign;
import com.bhukkad.restaurant.domain.repository.PromotionCampaignRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromotionAdminServiceTest {

    @Mock
    private PromotionCampaignRepository promotionCampaignRepository;

    @InjectMocks
    private PromotionAdminService service;

    @Test
    void create_persistsCampaign() {
        when(promotionCampaignRepository.save(any(PromotionCampaign.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        PromotionCampaign saved = service.create(
                "Welcome", "New user welcome",
                new BigDecimal("15.00"), new BigDecimal("100.00"),
                LocalDateTime.now(), LocalDateTime.now().plusDays(7),
                null);

        assertThat(saved.getName()).isEqualTo("Welcome");
        assertThat(saved.getActive()).isTrue();
        assertThat(saved.getDiscountPct()).isEqualByComparingTo("15.00");
    }

    @Test
    void update_appliesProvidedFields() {
        PromotionCampaign existing = new PromotionCampaign();
        existing.setId(1L);
        existing.setName("Old");
        existing.setDiscountPct(new BigDecimal("5.00"));
        when(promotionCampaignRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(promotionCampaignRepository.save(any(PromotionCampaign.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        PromotionCampaign updated = service.update(1L, "New", null,
                new BigDecimal("10.00"), null, null, null, null);

        assertThat(updated.getName()).isEqualTo("New");
        assertThat(updated.getDiscountPct()).isEqualByComparingTo("10.00");
    }

    @Test
    void update_throws_whenMissing() {
        when(promotionCampaignRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(99L, null, null, null, null, null, null, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deactivate_setsInactiveFlag() {
        PromotionCampaign existing = new PromotionCampaign();
        existing.setId(1L);
        existing.setActive(true);
        when(promotionCampaignRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(promotionCampaignRepository.save(any(PromotionCampaign.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.deactivate(1L);

        assertThat(existing.getActive()).isFalse();
    }

    @Test
    void listAll_returnsAllCampaigns() {
        when(promotionCampaignRepository.findAll()).thenReturn(List.of(new PromotionCampaign()));
        assertThat(service.listAll()).hasSize(1);
    }
}