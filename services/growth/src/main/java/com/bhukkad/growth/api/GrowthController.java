package com.bhukkad.growth.api;

import com.bhukkad.growth.dto.CampaignResponse;
import com.bhukkad.growth.dto.LoyaltyPointsResponse;
import com.bhukkad.growth.dto.ReferralStatsResponse;
import com.bhukkad.growth.service.CampaignService;
import com.bhukkad.growth.service.LoyaltyService;
import com.bhukkad.growth.service.ReferralTrackingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
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
    public ResponseEntity<LoyaltyPointsResponse> getLoyaltyPoints(@PathVariable Long customerId) {
        log.debug("Getting loyalty points for customer {}", customerId);
        return ResponseEntity.ok(loyaltyService.getLoyaltyPoints(customerId));
    }

    @PostMapping("/customers/{customerId}/loyalty/credit")
    public ResponseEntity<Void> creditPoints(
            @PathVariable Long customerId,
            @RequestParam int points,
            @RequestParam String reason) {
        loyaltyService.creditPoints(customerId, points, reason);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/customers/{customerId}/loyalty/redeem")
    public ResponseEntity<Map<String, Boolean>> redeemPoints(
            @PathVariable Long customerId,
            @RequestParam int points) {
        boolean success = loyaltyService.redeemPoints(customerId, points);
        return ResponseEntity.ok(Map.of("success", success));
    }

    @GetMapping("/customers/{customerId}/loyalty/calculate")
    public ResponseEntity<Map<String, Integer>> calculatePointsForOrder(
            @PathVariable Long customerId,
            @RequestParam double orderAmount) {
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
    public ResponseEntity<ReferralStatsResponse> getReferralStats(@PathVariable Long customerId) {
        log.debug("Getting referral stats for customer {}", customerId);
        return ResponseEntity.ok(referralService.getReferralStats(customerId));
    }

    @GetMapping("/customers/{customerId}/referral/code")
    public ResponseEntity<Map<String, String>> getReferralCode(@PathVariable Long customerId) {
        String code = referralService.generateReferralCode(customerId);
        return ResponseEntity.ok(Map.of("referralCode", code));
    }

    @PostMapping("/referral/apply")
    public ResponseEntity<Map<String, Boolean>> applyReferral(
            @RequestParam Long referrerId,
            @RequestParam Long referredUserId) {
        boolean success = referralService.applyReferralReward(referrerId, referredUserId);
        return ResponseEntity.ok(Map.of("success", success));
    }
}
