package com.bhukkad.growth.api.controller;

import com.bhukkad.common.ratelimit.RateLimitService;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.growth.api.dto.response.CampaignResponse;
import com.bhukkad.growth.config.GrowthProperties;
import com.bhukkad.growth.domain.service.CampaignService;
import com.bhukkad.growth.domain.service.LoyaltyCreditService;
import com.bhukkad.growth.domain.service.LoyaltyService;
import com.bhukkad.growth.domain.service.ReferralTrackingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Campaign and referral controller endpoints on top of the existing
 * security-perimeter matrix: subject-or-admin gating for the per-customer
 * referral views and the public campaign read surface.
 */
@ExtendWith(MockitoExtension.class)
class GrowthControllerSurfaceTest {

    @Mock private LoyaltyService loyaltyService;
    @Mock private LoyaltyCreditService loyaltyCreditService;
    @Mock private CampaignService campaignService;
    @Mock private ReferralTrackingService referralService;
    @Mock private RateLimitService rateLimitService;
    @Mock private GrowthProperties growthProperties;

    @InjectMocks private GrowthController controller;

    private static TokenPrincipal principal(long id, String scope) {
        return new TokenPrincipal(id, "u@t.test", scope);
    }

    @Test
    void calculatePoints_ownCustomer_delegates() {
        when(loyaltyService.calculatePointsForOrder(99.9)).thenReturn(99);

        var response = controller.calculatePointsForOrder(principal(1L, "CUSTOMER"), 1L, 99.9);

        assertThat(response.getBody()).containsEntry("points", 99);
    }

    @Test
    void calculatePoints_crossCustomer_denied() {
        assertThatThrownBy(() ->
                controller.calculatePointsForOrder(principal(2L, "CUSTOMER"), 1L, 10))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void activeCampaigns_public_delegates() {
        List<CampaignResponse> campaigns = List.of(new CampaignResponse());
        when(campaignService.getActiveCampaigns()).thenReturn(campaigns);

        assertThat(controller.getActiveCampaigns().getBody()).isSameAs(campaigns);
    }

    @Test
    void campaign_found_returns200() {
        CampaignResponse campaign = new CampaignResponse();
        when(campaignService.getCampaignById(5L)).thenReturn(campaign);

        var response = controller.getCampaign(5L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(campaign);
    }

    @Test
    void campaign_missing_returns404() {
        when(campaignService.getCampaignById(5L)).thenReturn(null);

        assertThat(controller.getCampaign(5L).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void bestDiscount_delegates() {
        when(campaignService.calculateBestDiscount(500.0)).thenReturn(75.5);

        assertThat(controller.calculateBestDiscount(500.0).getBody())
                .containsEntry("discount", 75.5);
    }

    @Test
    void referralStats_adminCanReadOther() {
        var stats = com.bhukkad.growth.api.dto.response.ReferralStatsResponse.builder()
                .customerId(1L).build();
        when(referralService.getReferralStats(1L)).thenReturn(stats);

        assertThat(controller.getReferralStats(principal(9L, "ADMIN"), 1L).getBody()).isSameAs(stats);
    }

    @Test
    void referralStats_crossCustomer_denied() {
        assertThatThrownBy(() ->
                controller.getReferralStats(principal(2L, "CUSTOMER"), 1L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void referralCode_ownCustomer_returnsDelegatedCode() {
        when(referralService.generateReferralCode(1L)).thenReturn("BK42");

        var response = controller.getReferralCode(principal(1L, "CUSTOMER"), 1L);

        assertThat(response.getBody()).containsEntry("referralCode", "BK42");
        verify(referralService).generateReferralCode(1L);
    }

    @Test
    void referralCode_nullPrincipal_denied() {
        assertThatThrownBy(() -> controller.getReferralCode(null, 1L))
                .isInstanceOf(AccessDeniedException.class);
    }
}
