package com.bhukkad.growth.api.controller;

import com.bhukkad.common.ratelimit.RateLimitDecision;
import com.bhukkad.common.ratelimit.RateLimitService;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.growth.config.GrowthProperties;
import com.bhukkad.growth.api.dto.response.CampaignResponse;
import com.bhukkad.growth.api.dto.response.LoyaltyPointsResponse;
import com.bhukkad.growth.api.dto.response.ReferralStatsResponse;
import com.bhukkad.growth.domain.service.CampaignService;
import com.bhukkad.growth.domain.service.LoyaltyService;
import com.bhukkad.growth.domain.service.ReferralTrackingService;
import com.bhukkad.growth.domain.service.LoyaltyCreditService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Per-endpoint unit matrix for the growth surface: loyalty reads/redemption
 * are subject-or-admin; points crediting requires a SERVICE principal (the
 * mesh service-JWT) or an ADMIN user JWT plus an idempotency key; referral
 * attribution binds the referred user to the JWT subject behind per-customer
 * and per-IP ceilings.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GrowthEndpointSecurityTest {

    private static TokenPrincipal principal(long userId, String scope) {
        return new TokenPrincipal(userId, "u@t.test", scope);
    }

    @Mock private LoyaltyService loyaltyService;
    @Mock private LoyaltyCreditService loyaltyCreditService;
    @Mock private CampaignService campaignService;
    @Mock private ReferralTrackingService referralService;
    @Mock private RateLimitService rateLimitService;
    /** Real defaults (reward/cap numbers) — W1-LOYALTY wired this into the controller. */
    @org.mockito.Spy private GrowthProperties growthProperties = new GrowthProperties();

    @InjectMocks
    private GrowthController controller;

    @Test
    void loyalty_crossCustomerRead_throws() {
        assertThatThrownBy(() -> controller.getLoyaltyPoints(principal(2L, "CUSTOMER"), 1L))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void loyalty_ownRead_allowed() {
        when(loyaltyService.getLoyaltyPoints(1L)).thenReturn(
                LoyaltyPointsResponse.builder().customerId(1L).build());

        assertThat(controller.getLoyaltyPoints(principal(1L, "CUSTOMER"), 1L).getBody())
                .isNotNull();
    }

    @Test
    void loyaltyCredit_declaresServiceOrAdminGate() throws Exception {
        var method = GrowthController.class.getMethod("creditPoints",
                Long.class, int.class, String.class, String.class);
        var gate = method.getAnnotation(
                org.springframework.security.access.prepost.PreAuthorize.class);
        assertThat(gate).isNotNull();
        // ADR-005: the credit surface is mesh-service operated; ADMIN stays
        // for the back-office console.
        assertThat(gate.value()).contains("SERVICE");
        assertThat(gate.value()).contains("ADMIN");
    }

    @Test
    void loyaltyCredit_requiresIdempotencyKeyAndDelegates() {
        var response = controller.creditPoints(1L, 100, "ORDER_REWARD", "order:42");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(loyaltyCreditService).credit(1L, 100, "ORDER_REWARD", "order:42");
    }

    @Test
    void loyaltyRedeem_crossCustomer_throws() {
        assertThatThrownBy(() -> controller.redeemPoints(principal(2L, "CUSTOMER"), 1L, 100))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void loyaltyRedeem_ownBalance_allowed() {
        when(loyaltyService.redeemPoints(1L, 100)).thenReturn(true);

        assertThat(controller.redeemPoints(principal(1L, "CUSTOMER"), 1L, 100).getBody())
                .containsEntry("success", true);
    }

    @Test
    void referralStats_crossCustomer_throws() {
        assertThatThrownBy(() -> controller.getReferralStats(principal(2L, "CUSTOMER"), 1L))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void referralApply_bindsReferredUserToPrincipal_behindCeilings() {
        when(rateLimitService.check(anyString(), anyString(), anyLong(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(RateLimitDecision.allowed(1L, 5L, 86_400L));
        when(referralService.applyReferralReward(5L, 7L)).thenReturn(true);

        var response = controller.applyReferral(principal(7L, "CUSTOMER"), 5L);

        assertThat(response.getBody()).containsEntry("success", true);
        verify(referralService).applyReferralReward(5L, 7L);
        // Per-customer AND per-IP ceilings are consulted before the apply.
        verify(rateLimitService).check(eq("referral-apply"), eq("customer:7"),
                anyLong(), org.mockito.ArgumentMatchers.anyInt());
        verify(rateLimitService).check(eq("referral-apply"), eq("ip:" + com.bhukkad.common.web.RequestUtils.UNKNOWN_IP),
                anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void referralApply_rateLimited_throws429() {
        when(rateLimitService.check(anyString(), anyString(), anyLong(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(RateLimitDecision.denied(6L, 5L, 42L));

        assertThatThrownBy(() -> controller.applyReferral(principal(7L, "CUSTOMER"), 5L))
                .isInstanceOf(com.bhukkad.common.ratelimit.RateLimitExceededException.class);
    }

    @Test
    void referralApply_selfReferral_rejected() {
        var response = controller.applyReferral(principal(7L, "CUSTOMER"), 7L);

        assertThat(response.getBody()).containsEntry("success", false);
    }
}
