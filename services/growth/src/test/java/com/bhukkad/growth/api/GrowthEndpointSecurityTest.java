package com.bhukkad.growth.api;

import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.growth.service.CampaignService;
import com.bhukkad.growth.service.LoyaltyService;
import com.bhukkad.growth.service.ReferralTrackingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Per-endpoint unit matrix for the growth surface: loyalty reads/redemption
 * are subject-or-admin; points crediting is pinned to an ADMIN gate; referral
 * attribution binds the referred user to the JWT subject.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GrowthEndpointSecurityTest {

    private static TokenPrincipal principal(long userId, String scope) {
        return new TokenPrincipal(userId, "u@t.test", scope);
    }

    @Mock private LoyaltyService loyaltyService;
    @Mock private CampaignService campaignService;
    @Mock private ReferralTrackingService referralService;
    @InjectMocks private GrowthController controller;

    @Test
    void loyalty_crossCustomerRead_throws() {
        assertThatThrownBy(() -> controller.getLoyaltyPoints(principal(2L, "CUSTOMER"), 1L))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void loyalty_ownRead_allowed() {
        when(loyaltyService.getLoyaltyPoints(1L)).thenReturn(
                com.bhukkad.growth.dto.LoyaltyPointsResponse.builder().customerId(1L).build());

        assertThat(controller.getLoyaltyPoints(principal(1L, "CUSTOMER"), 1L).getBody())
                .isNotNull();
    }

    @Test
    void loyaltyCredit_declaresAdminGate() throws Exception {
        var method = GrowthController.class.getMethod("creditPoints",
                Long.class, int.class, String.class);
        var gate = method.getAnnotation(
                org.springframework.security.access.prepost.PreAuthorize.class);
        assertThat(gate).isNotNull();
        assertThat(gate.value()).contains("ADMIN");
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
    void referralApply_bindsReferredUserToPrincipal() {
        when(referralService.applyReferralReward(5L, 7L)).thenReturn(true);

        var response = controller.applyReferral(principal(7L, "CUSTOMER"), 5L);

        assertThat(response.getBody()).containsEntry("success", true);
        verify(referralService).applyReferralReward(5L, 7L);
    }

    @Test
    void referralApply_selfReferral_rejected() {
        var response = controller.applyReferral(principal(7L, "CUSTOMER"), 7L);

        assertThat(response.getBody()).containsEntry("success", false);
    }
}
