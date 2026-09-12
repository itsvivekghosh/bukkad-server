package com.bhukkad.referral.api.controller;

import com.bhukkad.common.error.UnauthorizedException;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.referral.api.dto.request.AffiliateCodeRequest;
import com.bhukkad.referral.api.dto.request.AffiliateSignupRequest;
import com.bhukkad.referral.api.dto.request.ApplyReferralRequest;
import com.bhukkad.referral.api.dto.request.InternalCodeRequest;
import com.bhukkad.referral.api.dto.request.ReferralValidateRequest;
import com.bhukkad.referral.api.dto.response.AffiliateCodeResponse;
import com.bhukkad.referral.api.dto.response.AffiliateStatsResponse;
import com.bhukkad.referral.api.dto.response.ReferralInfoResponse;
import com.bhukkad.referral.domain.service.AffiliateService;
import com.bhukkad.referral.domain.service.ReferralService;
import com.bhukkad.referral.domain.service.ReferralService.ApplyReferralOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReferralControllerTest {

    @Mock private ReferralService referralService;
    @Mock private AffiliateService affiliateService;

    @InjectMocks private ReferralController controller;

    private static TokenPrincipal principal(Long id, String scope) {
        return id == null ? null : new TokenPrincipal(id, "u@t.test", scope);
    }

    // ------------------------------------------------------------------
    // getReferralInfo (subject-or-admin)
    // ------------------------------------------------------------------

    @Test
    void getReferralInfo_nullPrincipal_throwsUnauthorized() {
        assertThatThrownBy(() -> controller.getReferralInfo(null, 1L))
                .isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> controller.getReferralInfo(principal(null, "CUSTOMER"), 1L))
                .isInstanceOf(UnauthorizedException.class);
        verifyNoInteractions(referralService);
    }

    @Test
    void getReferralInfo_otherCustomer_throwsAccessDenied() {
        assertThatThrownBy(() ->
                controller.getReferralInfo(principal(2L, "CUSTOMER"), 1L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void getReferralInfo_self_returnsInfo() {
        var info = new ReferralInfoResponse("BK1", 2, 100.0);
        when(referralService.getReferralInfo(1L)).thenReturn(info);

        var response = controller.getReferralInfo(principal(1L, "CUSTOMER"), 1L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().success()).isTrue();
        assertThat(response.getBody().data()).isSameAs(info);
    }

    @Test
    void getReferralInfo_nullScopeOtherCustomer_denied() {
        assertThatThrownBy(() -> controller.getReferralInfo(principal(2L, null), 1L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void generateReferralCode_adminOnOtherCustomer_allowed() {
        when(referralService.generateAndSaveReferralCode(1L)).thenReturn("BKADMIN");

        var response = controller.generateReferralCode(principal(99L, "ADMIN"), 1L);

        assertThat(response.getBody().data()).containsEntry("referralCode", "BKADMIN");
    }

    @Test
    void generateReferralCode_otherCustomer_denied() {
        assertThatThrownBy(() ->
                controller.generateReferralCode(principal(2L, "CUSTOMER"), 1L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void generateReferralCode_nullPrincipal_throwsUnauthorized() {
        assertThatThrownBy(() -> controller.generateReferralCode(null, 1L))
                .isInstanceOf(UnauthorizedException.class);
    }

    // ------------------------------------------------------------------
    // self surface
    // ------------------------------------------------------------------

    @Test
    void generateMyReferralCode_missingPrincipal_returns401() {
        var response = controller.generateMyReferralCode(null);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody().success()).isFalse();
    }

    @Test
    void generateMyReferralCode_authenticated_delegates() {
        when(referralService.generateAndSaveReferralCode(5L)).thenReturn("BK5");

        var response = controller.generateMyReferralCode(principal(5L, "CUSTOMER"));

        assertThat(response.getBody().data()).containsEntry("referralCode", "BK5");
    }

    @Test
    void validateReferralCode_post_delegates() {
        when(referralService.isValidReferralCode("BK9")).thenReturn(true);

        var response = controller.validateReferralCode(new ReferralValidateRequest("BK9"));

        assertThat(response.getBody().data()).isTrue();
    }

    @Test
    void validateReferralCode_get_delegates() {
        when(referralService.isValidReferralCode("BK9")).thenReturn(false);

        var response = controller.validateReferralCode("BK9");

        assertThat(response.getBody().data()).isFalse();
    }

    @Test
    void getMyReferralRewards_missingPrincipal_returns401() {
        var response = controller.getMyReferralRewards(null);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody().success()).isFalse();
    }

    @Test
    void getMyReferralRewards_authenticated_delegates() {
        var info = new ReferralInfoResponse("BK5", 1, 50.0);
        when(referralService.getReferralInfo(5L)).thenReturn(info);

        assertThat(controller.getMyReferralRewards(principal(5L, "CUSTOMER")).getBody().data())
                .isSameAs(info);
    }

    // ------------------------------------------------------------------
    // internal apply/code/affiliate-signup
    // ------------------------------------------------------------------

    @Test
    void applyReferral_firstBinding_includesReferrerId() {
        when(referralService.applyReferral(2L, "a@b.c", "BK1"))
                .thenReturn(ApplyReferralOutcome.firstBinding(9L));

        var response = controller.applyReferral(new ApplyReferralRequest("BK1", 2L, "a@b.c"));

        Map<String, Object> body = response.getBody().data();
        assertThat(body).containsEntry("applied", true)
                .containsEntry("firstBinding", true)
                .containsEntry("referrerCustomerId", 9L);
    }

    @Test
    void applyReferral_notApplied_omitsReferrerId() {
        when(referralService.applyReferral(2L, "a@b.c", "BK1"))
                .thenReturn(ApplyReferralOutcome.notApplied());

        var response = controller.applyReferral(new ApplyReferralRequest("BK1", 2L, "a@b.c"));

        assertThat(response.getBody().data())
                .containsEntry("applied", false)
                .doesNotContainKey("referrerCustomerId");
    }

    @Test
    void generateCodeInternal_delegates() {
        when(referralService.generateAndSaveReferralCode(3L)).thenReturn("BK3");

        var response = controller.generateCodeInternal(new InternalCodeRequest(3L));

        assertThat(response.getBody().data()).containsEntry("referralCode", "BK3");
        assertThat(response.getBody().success()).isTrue();
    }

    @Test
    void recordAffiliateSignup_delegates() {
        var response = controller.recordAffiliateSignup(new AffiliateSignupRequest("TOP", 4L, "a@b.c"));

        verify(affiliateService).recordSignup("TOP", 4L, "a@b.c");
        assertThat(response.getStatusCode().value()).isEqualTo(202);
    }

    // ------------------------------------------------------------------
    // affiliate admin surface
    // ------------------------------------------------------------------

    @Test
    void listAffiliates_delegates() {
        List<AffiliateCodeResponse> list = List.of(new AffiliateCodeResponse());
        when(affiliateService.listAll()).thenReturn(list);

        assertThat(controller.listAffiliates().getBody().data()).isSameAs(list);
    }

    @Test
    void createAffiliate_delegates() {
        AffiliateCodeRequest request = new AffiliateCodeRequest();
        AffiliateCodeResponse created = new AffiliateCodeResponse();
        when(affiliateService.create(request)).thenReturn(created);

        var response = controller.createAffiliate(request);

        assertThat(response.getBody().data()).isSameAs(created);
        assertThat(response.getBody().message()).contains("created");
    }

    @Test
    void updateAffiliate_delegates() {
        AffiliateCodeRequest request = new AffiliateCodeRequest();
        AffiliateCodeResponse updated = new AffiliateCodeResponse();
        when(affiliateService.update(1L, request)).thenReturn(updated);

        assertThat(controller.updateAffiliate(1L, request).getBody().data()).isSameAs(updated);
    }

    @Test
    void deactivateAffiliate_delegates() {
        var response = controller.deactivateAffiliate(1L);

        verify(affiliateService).deactivate(1L);
        assertThat(response.getBody().message()).contains("deactivated");
    }

    @Test
    void affiliateStats_delegates() {
        AffiliateStatsResponse stats = new AffiliateStatsResponse(1L, "TOP", "N", 0, 0, 0, List.of());
        when(affiliateService.getStats(1L)).thenReturn(stats);

        assertThat(controller.affiliateStats(1L).getBody().data()).isSameAs(stats);
    }
}
