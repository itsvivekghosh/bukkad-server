package com.bhukkad.growth.api;

import com.bhukkad.common.security.PrincipalGuard;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.growth.dto.CampaignResponse;
import com.bhukkad.growth.dto.LoyaltyPointsResponse;
import com.bhukkad.growth.dto.ReferralStatsResponse;
import com.bhukkad.growth.service.CampaignService;
import com.bhukkad.growth.service.LoyaltyService;
import com.bhukkad.growth.service.ReferralTrackingService;
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
    private final CampaignService campaignService;
    private final ReferralTrackingService referralService;

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
     * Points credit is a back-office / service operation. Any authenticated
     * caller previously minted unlimited points for any customer.
     */
    @PostMapping("/customers/{customerId}/loyalty/credit")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> creditPoints(
            @PathVariable Long customerId,
            @RequestParam int points,
            @RequestParam String reason) {
        loyaltyService.creditPoints(customerId, points, reason);
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
     * a farmable free-form pair previously inflated referrer rewards.
     */
    @PostMapping("/referral/apply")
    public ResponseEntity<Map<String, Boolean>> applyReferral(
            @AuthenticationPrincipal TokenPrincipal principal,
            @RequestParam Long referrerId) {
        PrincipalGuard.requireAuthenticated(principal);
        if (referrerId.equals(principal.userId())) {
            return ResponseEntity.ok(Map.of("success", false));
        }
        boolean success = referralService.applyReferralReward(referrerId, principal.userId());
        return ResponseEntity.ok(Map.of("success", success));
    }
}
