package com.bhukkad.growth.api.controller;

import com.bhukkad.common.ratelimit.RateLimitDecision;
import com.bhukkad.common.ratelimit.RateLimitExceededException;
import com.bhukkad.common.ratelimit.RateLimitService;
import com.bhukkad.common.security.PrincipalGuard;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.common.web.RequestUtils;
import com.bhukkad.growth.api.dto.response.CampaignResponse;
import com.bhukkad.growth.api.dto.response.LoyaltyPointsResponse;
import com.bhukkad.growth.api.dto.response.ReferralStatsResponse;
import com.bhukkad.growth.domain.service.CampaignService;
import com.bhukkad.growth.domain.service.LoyaltyService;
import com.bhukkad.growth.domain.service.ReferralTrackingService;
import com.bhukkad.growth.domain.service.LoyaltyCreditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class GrowthController {

    private final LoyaltyService loyaltyService;
    private final LoyaltyCreditService loyaltyCreditService;
    private final CampaignService campaignService;
    private final ReferralTrackingService referralService;
    private final RateLimitService rateLimitService;
    private final com.bhukkad.growth.config.GrowthProperties growthProperties;

    // ============= Loyalty Endpoints =============

    @GetMapping("/customers/{customerId}/loyalty")
    public ResponseEntity<LoyaltyPointsResponse> getLoyaltyPoints(
            @AuthenticationPrincipal TokenPrincipal principal,
            @PathVariable Long customerId) {
        PrincipalGuard.requireSelfOrAdmin(principal, customerId);
        log.debug("Getting loyalty points for customer {}", customerId);
        return ResponseEntity.ok(loyaltyService.getLoyaltyPoints(customerId));
    }

    /**
     * Points credit is a back-office / mesh-service operation (ADR-005): the
     * caller must be a service principal (ServiceJwtAuthFilter grants
     * ROLE_SERVICE from the shared-mesh {@code X-Service-Token}) or an ADMIN
     * user JWT, must present an {@code Idempotency-Key} (scope
     * {@code LOYALTY_CREDIT}) and is subject to the per-customer daily credit
     * cap (422 on breach).
     */
    @PostMapping("/customers/{customerId}/loyalty/credit")
    @PreAuthorize("hasRole('SERVICE') or hasRole('ADMIN')")
    public ResponseEntity<Void> creditPoints(
            @PathVariable Long customerId,
            @RequestParam int points,
            @RequestParam String reason,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        loyaltyCreditService.credit(customerId, points, reason, idempotencyKey);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/customers/{customerId}/loyalty/redeem")
    public ResponseEntity<Map<String, Boolean>> redeemPoints(
            @AuthenticationPrincipal TokenPrincipal principal,
            @PathVariable Long customerId,
            @RequestParam int points) {
        // Redeem spends the caller's own balance only.
        PrincipalGuard.requireSelfOrAdmin(principal, customerId);
        boolean success = loyaltyService.redeemPoints(customerId, points);
        return ResponseEntity.ok(Map.of("success", success));
    }

    @GetMapping("/customers/{customerId}/loyalty/calculate")
    public ResponseEntity<Map<String, Integer>> calculatePointsForOrder(
            @AuthenticationPrincipal TokenPrincipal principal,
            @PathVariable Long customerId,
            @RequestParam double orderAmount) {
        PrincipalGuard.requireSelfOrAdmin(principal, customerId);
        int points = loyaltyService.calculatePointsForOrder(orderAmount);
        return ResponseEntity.ok(Map.of("points", points));
    }

    // ============= Campaign Endpoints =============

    @GetMapping("/campaigns/active")
    public ResponseEntity<List<CampaignResponse>> getActiveCampaigns() {
        log.debug("Getting active campaigns");
        return ResponseEntity.ok(campaignService.getActiveCampaigns());
    }

    @GetMapping("/campaigns/{campaignId}")
    public ResponseEntity<CampaignResponse> getCampaign(@PathVariable Long campaignId) {
        CampaignResponse campaign = campaignService.getCampaignById(campaignId);
        if (campaign == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(campaign);
    }

    @GetMapping("/campaigns/best-discount")
    public ResponseEntity<Map<String, Double>> calculateBestDiscount(@RequestParam double subtotal) {
        double discount = campaignService.calculateBestDiscount(subtotal);
        return ResponseEntity.ok(Map.of("discount", discount));
    }

    // ============= Referral Endpoints =============

    @GetMapping("/customers/{customerId}/referral/stats")
    public ResponseEntity<ReferralStatsResponse> getReferralStats(
            @AuthenticationPrincipal TokenPrincipal principal,
            @PathVariable Long customerId) {
        PrincipalGuard.requireSelfOrAdmin(principal, customerId);
        log.debug("Getting referral stats for customer {}", customerId);
        return ResponseEntity.ok(referralService.getReferralStats(customerId));
    }

    @GetMapping("/customers/{customerId}/referral/code")
    public ResponseEntity<Map<String, String>> getReferralCode(
            @AuthenticationPrincipal TokenPrincipal principal,
            @PathVariable Long customerId) {
        PrincipalGuard.requireSelfOrAdmin(principal, customerId);
        String code = referralService.generateReferralCode(customerId);
        return ResponseEntity.ok(Map.of("referralCode", code));
    }

    /**
     * Referral attribution: the referred user is the authenticated caller —
     * a farmable free-form pair previously inflated referrer rewards. Abuse
     * ceilings (audit feature #4): per-customer and per-IP atomic rate limits
     * plus the one-active-referral guard in the tracking service.
     */
    @PostMapping("/referral/apply")
    public ResponseEntity<Map<String, Boolean>> applyReferral(
            @AuthenticationPrincipal TokenPrincipal principal,
            @RequestParam Long referrerId) {
        PrincipalGuard.requireAuthenticated(principal);
        if (referrerId.equals(principal.userId())) {
            return ResponseEntity.ok(Map.of("success", false));
        }
        assertApplyNotRateLimited("customer:" + principal.userId(),
                growthProperties.getReferral().getApplyPerCustomerPerDay());
        assertApplyNotRateLimited("ip:" + RequestUtils.resolveClientIp(),
                growthProperties.getReferral().getApplyPerIpPerDay());
        boolean success = referralService.applyReferralReward(referrerId, principal.userId());
        return ResponseEntity.ok(Map.of("success", success));
    }

    /** Atomic fixed-window per-day ceiling (RedisRateLimitService fail-open policy applies). */
    private void assertApplyNotRateLimited(String identifier, long limit) {
        RateLimitDecision decision = rateLimitService.check("referral-apply", identifier,
                limit, 86_400);
        if (!decision.allowed()) {
            throw new RateLimitExceededException(
                    "Too many referral applications. Try again later.", decision.retryAfterSeconds());
        }
    }
}
