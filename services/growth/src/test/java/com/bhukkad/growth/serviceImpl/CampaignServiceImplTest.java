package com.bhukkad.growth.serviceImpl;

import com.bhukkad.growth.config.GrowthProperties;
import com.bhukkad.growth.dto.CampaignResponse;
import com.bhukkad.growth.entity.PromotionCampaign;
import com.bhukkad.growth.repository.PromotionCampaignRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * P3 structural (dead-stub sweep): the campaign reads were placeholder code
 * reading Redis keys nothing ever wrote. They now serve the
 * {@code promotion_campaigns} table; these tests pin the honest mapping,
 * the active-window delegation, and the response-stable DTO shape.
 */
@ExtendWith(MockitoExtension.class)
class CampaignServiceImplTest {

    @Mock private PromotionCampaignRepository campaignRepository;

    private CampaignServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CampaignServiceImpl(campaignRepository, new GrowthProperties());
    }

    private PromotionCampaign campaign(Long id, String name, String percent, String flat,
                                       String minOrder, String maxDiscount) {
        PromotionCampaign c = new PromotionCampaign();
        c.setId(id);
        c.setName(name);
        c.setCampaignType("PERCENTAGE");
        c.setDescription(name + " desc");
        c.setDiscountPercent(percent == null ? null : new BigDecimal(percent));
        c.setFlatDiscountAmount(flat == null ? null : new BigDecimal(flat));
        c.setMinOrderAmount(minOrder == null ? null : new BigDecimal(minOrder));
        c.setMaxDiscountAmount(maxDiscount == null ? null : new BigDecimal(maxDiscount));
        c.setFreeDelivery(true);
        c.setPriority(7);
        c.setActive(true);
        c.setBuyQuantity(2);
        c.setGetQuantity(1);
        c.setGetDiscountPercent(50);
        c.setTargetSegment("NEW_USERS");
        return c;
    }

    @Test
    void getActiveCampaigns_mapsFullResponseIncludingBogofFields() {
        PromotionCampaign c = campaign(1L, "Summer", "20", null, "100", "500");
        c.setStartsAt(LocalDateTime.now().minusDays(1));
        c.setEndsAt(LocalDateTime.now().plusDays(1));
        when(campaignRepository.findActiveCampaigns(any())).thenReturn(List.of(c));

        List<CampaignResponse> result = service.getActiveCampaigns();

        assertThat(result).hasSize(1);
        CampaignResponse r = result.get(0);
        assertThat(r.getId()).isEqualTo(1L);
        assertThat(r.getName()).isEqualTo("Summer");
        assertThat(r.getDiscountPercent()).isEqualTo(20.0);
        assertThat(r.getMinOrderAmount()).isEqualTo(100.0);
        assertThat(r.getMaxDiscountAmount()).isEqualTo(500.0);
        assertThat(r.getFlatDiscountAmount()).isNull();
        assertThat(r.getFreeDelivery()).isTrue();
        assertThat(r.getPriority()).isEqualTo(7);
        assertThat(r.getIsActive()).isTrue();
        assertThat(r.getBuyQuantity()).isEqualTo(2);
        assertThat(r.getGetQuantity()).isEqualTo(1);
        assertThat(r.getGetDiscountPercent()).isEqualTo(50);
        assertThat(r.getTargetSegment()).isEqualTo("NEW_USERS");
    }

    @Test
    void calculateBestDiscount_picksWinnerRespectingMinOrderAndCaps() {
        // 10% of 1000 = 100 but flat 150 on the bigger cart; min-order gates the small cart.
        PromotionCampaign pct = campaign(1L, "Pct10", "10", null, null, null);
        PromotionCampaign flatBig = campaign(2L, "Flat150", null, "150", "800", null);
        when(campaignRepository.findActiveCampaigns(any())).thenReturn(List.of(pct, flatBig));

        assertThat(service.calculateBestDiscount(500)).isEqualTo(50.0);   // flat gated by min-order
        assertThat(service.calculateBestDiscount(1000)).isEqualTo(150.0); // flat beats percent
    }

    @Test
    void calculateBestDiscount_clampedToConfiguredMaxDiscountPercent() {
        GrowthProperties props = new GrowthProperties();
        props.getCampaign().setMaxDiscountPercent(25);
        CampaignServiceImpl clamped = new CampaignServiceImpl(campaignRepository, props);
        PromotionCampaign pct = campaign(1L, "Pct60", "60", null, null, null);
        when(campaignRepository.findActiveCampaigns(any())).thenReturn(List.of(pct));

        assertThat(clamped.calculateBestDiscount(1000)).isEqualTo(250.0);
    }

    @Test
    void calculateBestDiscount_noActiveCampaigns_returnsZero() {
        when(campaignRepository.findActiveCampaigns(any())).thenReturn(List.of());
        assertThat(service.calculateBestDiscount(100)).isZero();
    }

    @Test
    void getCampaignById_activeWindowRow_mapsResponse() {
        PromotionCampaign c = campaign(9L, "Nine", "5", null, null, null);
        when(campaignRepository.findActiveCampaign(eq(9L), any())).thenReturn(Optional.of(c));

        CampaignResponse r = service.getCampaignById(9L);

        assertThat(r).isNotNull();
        assertThat(r.getId()).isEqualTo(9L);
    }

    @Test
    void getCampaignById_inactiveOrExpired_returnsNullKeeping404() {
        when(campaignRepository.findActiveCampaign(eq(9L), any())).thenReturn(Optional.empty());
        assertThat(service.getCampaignById(9L)).isNull();
    }
}
