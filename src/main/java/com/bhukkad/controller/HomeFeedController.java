package com.bhukkad.controller;

import com.bhukkad.cache.HomeFeedCacheService;
import com.bhukkad.config.ApiPaths;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.MembershipPlanResponse;
import com.bhukkad.dto.response.PromoBannerResponse;
import com.bhukkad.dto.response.PromotionCampaignResponse;
import com.bhukkad.feed.PromoBannerService;
import com.bhukkad.membership.MembershipService;
import com.bhukkad.promotion.PromotionCampaignService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Public home feed: promo banners, active promotion campaigns and membership plans.
 *
 * <p>Every endpoint here is anonymous and non-personalised, and the composed
 * {@code /feed} call is issued on every app launch. All reads therefore go
 * through {@link HomeFeedCacheService}, a short-TTL read-through cache, so the
 * launch path costs one Redis (or in-process Caffeine) lookup instead of three
 * database queries. The per-list endpoints share cache entries with
 * {@code /feed}, so warming one warms the other.
 */
@RestController
@RequestMapping(ApiPaths.V1_PREFIX + "/home")
@RequiredArgsConstructor
public class HomeFeedController {

    private final PromoBannerService promoBannerService;
    private final PromotionCampaignService promotionCampaignService;
    private final MembershipService membershipService;
    private final HomeFeedCacheService homeFeedCacheService;

    /** Active promo banner carousel; served from the shared home feed cache. */
    @GetMapping("/banners")
    public ResponseEntity<ApiResponse<List<PromoBannerResponse>>> getBanners() {
        return ResponseEntity.ok(ApiResponse.success(
                homeFeedCacheService.getBanners(promoBannerService::listActive)));
    }

    /** Active promotion campaigns; served from the shared home feed cache. */
    @GetMapping("/campaigns")
    public ResponseEntity<ApiResponse<List<PromotionCampaignResponse>>> getCampaigns() {
        return ResponseEntity.ok(ApiResponse.success(
                homeFeedCacheService.getCampaigns(promotionCampaignService::listActive)));
    }

    /**
     * Composed app-launch payload: banners, campaigns and membership plans.
     *
     * <p>Each section is cached under its own key, so a miss on one section does
     * not force the others to be recomputed.
     */
    @GetMapping("/feed")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getHomeFeed() {
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "banners", homeFeedCacheService.getBanners(promoBannerService::listActive),
                "campaigns", homeFeedCacheService.getCampaigns(promotionCampaignService::listActive),
                "membershipPlans", homeFeedCacheService.getMembershipPlans(membershipService::listPlans))));
    }

    /** Public list of active membership plans; served from the shared home feed cache. */
    @GetMapping("/membership-plans")
    public ResponseEntity<ApiResponse<List<MembershipPlanResponse>>> getMembershipPlans() {
        return ResponseEntity.ok(ApiResponse.success(
                homeFeedCacheService.getMembershipPlans(membershipService::listPlans)));
    }
}
