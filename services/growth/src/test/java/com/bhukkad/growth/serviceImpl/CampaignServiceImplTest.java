package com.bhukkad.growth.serviceImpl;

import com.bhukkad.growth.config.GrowthProperties;
import com.bhukkad.growth.dto.CampaignResponse;
import com.bhukkad.growth.entity.PromotionCampaign;
import com.bhukkad.growth.repository.PromotionCampaignRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Campaign serving reads the persisted promotion_campaigns rows (P3 dead-code
 * fix: the previous implementation read Redis keys nobody wrote and returned
 * an always-empty list).
 */
@ExtendWith(MockitoExtension.class)
class CampaignServiceImplTest {

    @Mock private PromotionCampaignRepository campaignRepository;
    @Spy private GrowthProperties growthProperties = new GrowthProperties();
    @InjectMocks private CampaignServiceImpl service;

    private PromotionCampaign campaign(long id, String name, Double discountPercent,
                                       Double flat, Double min, Double max, int priority) {
        PromotionCampaign c = new PromotionCampaign();
        c.setId(id);
        c.setName(name);
        c.setCampaignType("PERCENTAGE");
        if (discountPercent != null) c.setDiscountPercent(BigDecimal.valueOf(discountPercent));
        if (flat != null) c.setFlatDiscountAmount(BigDecimal.valueOf(flat));
        if (min != null) c.setMinOrderAmount(BigDecimal.valueOf(min));
        if (max != null) c.setMaxDiscountAmount(BigDecimal.valueOf(max));
        c.setActive(true);
        c.setPriority(priority);
        return c;
    }

    @Test
    void getActiveCampaigns_mapsPersistedRows() {
        PromotionCampaign persisted = campaign(7L, "Monsoon Sale", 20.0, null, 100.0, 150.0, 5);
        persisted.setDescription("20% off");
        persisted.setFreeDelivery(true);
        persisted.setBuyQuantity(2);
        persisted.setGetQuantity(1);
        persisted.setGetDiscountPercent(50);
        persisted.setTargetSegment("NEW_USERS");
        persisted.setStartsAt(LocalDateTime.now().minusDays(1));
        persisted.setEndsAt(LocalDateTime.now().plusDays(1));
        when(campaignRepository.findCurrentlyActive(org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                .thenReturn(List.of(persisted));

        List<CampaignResponse> out = service.getActiveCampaigns();

        assertThat(out).hasSize(1);
        CampaignResponse response = out.get(0);
        assertThat(response.getId()).isEqualTo(7L);
        assertThat(response.getName()).isEqualTo("Monsoon Sale");
        assertThat(response.getDescription()).isEqualTo("20% off");
        assertThat(response.getDiscountPercent()).isEqualTo(20.0);
        assertThat(response.getMinOrderAmount()).isEqualTo(100.0);
        assertThat(response.getMaxDiscountAmount()).isEqualTo(150.0);
        assertThat(response.getFreeDelivery()).isTrue();
        assertThat(response.getPriority()).isEqualTo(5);
        assertThat(response.getIsActive()).isTrue();
        assertThat(response.getBuyQuantity()).isEqualTo(2);
        assertThat(response.getGetQuantity()).isEqualTo(1);
        assertThat(response.getGetDiscountPercent()).isEqualTo(50);
        assertThat(response.getTargetSegment()).isEqualTo("NEW_USERS");
    }

    @Test
    void getActiveCampaigns_emptyTable_returnsEmptyList() {
        when(campaignRepository.findCurrentlyActive(org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                .thenReturn(List.of());

        assertThat(service.getActiveCampaigns()).isEmpty();
    }

    @Test
    void getCampaignById_returnsMappedRow() {
        when(campaignRepository.findById(9L)).thenReturn(Optional.of(campaign(9L, "Flat 50", null, 50.0, null, null, 1)));

        CampaignResponse out = service.getCampaignById(9L);

        assertThat(out).isNotNull();
        assertThat(out.getName()).isEqualTo("Flat 50");
        assertThat(out.getFlatDiscountAmount()).isEqualTo(50.0);
    }

    @Test
    void getCampaignById_unknown_returnsNull() {
        when(campaignRepository.findById(404L)).thenReturn(Optional.empty());

        assertThat(service.getCampaignById(404L)).isNull();
        assertThat(service.getCampaignById(null)).isNull();
    }

    @Test
    void calculateBestDiscount_appliesPercentAndCaps() {
        // 20% of 500 = 100, under max 150 → best = 100.00
        when(campaignRepository.findCurrentlyActive(org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                .thenReturn(List.of(campaign(1L, "Pct", 20.0, null, 100.0, 150.0, 2)));

        assertThat(service.calculateBestDiscount(500.0)).isEqualTo(100.0);
    }

    @Test
    void calculateBestDiscount_respectsMinOrderAmount() {
        when(campaignRepository.findCurrentlyActive(org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                .thenReturn(List.of(campaign(1L, "Pct", 20.0, null, 1000.0, null, 2)));

        assertThat(service.calculateBestDiscount(500.0)).isZero();
    }
}
