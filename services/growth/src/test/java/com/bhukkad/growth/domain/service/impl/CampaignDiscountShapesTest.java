package com.bhukkad.growth.domain.service.impl;

import com.bhukkad.growth.api.dto.response.CampaignResponse;
import com.bhukkad.growth.config.GrowthProperties;
import com.bhukkad.growth.domain.entity.PromotionCampaign;
import com.bhukkad.growth.domain.repository.PromotionCampaignRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Discount-shape matrix complementing {@link CampaignServiceImplTest}:
 * flat-amount campaigns, max-amount/percentage-of-subtotal ceilings and the
 * null-safe entity mapping.
 */
@ExtendWith(MockitoExtension.class)
class CampaignDiscountShapesTest {

    @Mock private PromotionCampaignRepository campaignRepository;

    private CampaignServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CampaignServiceImpl(campaignRepository, new GrowthProperties());
    }

    private PromotionCampaign flat(String flat, String minOrder, String maxDiscount) {
        PromotionCampaign c = new PromotionCampaign();
        c.setId(1L);
        c.setName("Flat");
        c.setCampaignType("FLAT");
        c.setFlatDiscountAmount(flat == null ? null : new BigDecimal(flat));
        c.setMinOrderAmount(minOrder == null ? null : new BigDecimal(minOrder));
        c.setMaxDiscountAmount(maxDiscount == null ? null : new BigDecimal(maxDiscount));
        return c;
    }

    @Test
    void bestDiscount_flatAmountUsed() {
        when(campaignRepository.findActiveCampaigns(any())).thenReturn(List.of(flat("40", null, null)));

        assertThat(service.calculateBestDiscount(500)).isEqualTo(40.0);
    }

    @Test
    void bestDiscount_flatCappedByMaxDiscountAmount() {
        when(campaignRepository.findActiveCampaigns(any())).thenReturn(List.of(flat("300", null, "100")));

        assertThat(service.calculateBestDiscount(500)).isEqualTo(100.0);
    }

    @Test
    void bestDiscount_flatCappedByGlobalPercentCeiling() {
        // 300 flat on 500 subtotal but the config ceiling is 50% (250).
        when(campaignRepository.findActiveCampaigns(any())).thenReturn(List.of(flat("300", "600", null)));
        assertThat(service.calculateBestDiscount(500)).isZero(); // below min order

        PromotionCampaign eligible = flat("300", null, null);
        when(campaignRepository.findActiveCampaigns(any())).thenReturn(List.of(eligible));
        assertThat(service.calculateBestDiscount(500)).isEqualTo(250.0);
    }

    @Test
    void bestDiscount_noDiscountableFields_isZero() {
        when(campaignRepository.findActiveCampaigns(any())).thenReturn(List.of(flat(null, null, null)));

        assertThat(service.calculateBestDiscount(500)).isZero();
    }

    @Test
    void toResponse_nullMoneyColumns_mapToNullDoubles() {
        PromotionCampaign c = new PromotionCampaign();
        c.setId(2L);
        c.setName("Bogof");
        c.setCampaignType("BOGO");
        c.setBuyQuantity(2);
        c.setGetQuantity(1);
        when(campaignRepository.findActiveCampaigns(any())).thenReturn(List.of(c));

        CampaignResponse r = service.getActiveCampaigns().get(0);
        assertThat(r.getDiscountPercent()).isNull();
        assertThat(r.getFlatDiscountAmount()).isNull();
        assertThat(r.getMinOrderAmount()).isNull();
        assertThat(r.getMaxDiscountAmount()).isNull();
        assertThat(r.getBuyQuantity()).isEqualTo(2);
        assertThat(r.getGetQuantity()).isEqualTo(1);
    }

    @Test
    void percentCampaign_overridesFlatWhenBothSet() {
        PromotionCampaign c = flat("10", null, null);
        c.setDiscountPercent(new BigDecimal("10"));
        when(campaignRepository.findActiveCampaigns(any())).thenReturn(List.of(c));

        assertThat(service.calculateBestDiscount(500)).isEqualTo(50.0);
    }
}
