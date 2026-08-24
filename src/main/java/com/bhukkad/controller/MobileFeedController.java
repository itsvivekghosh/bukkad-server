package com.bhukkad.controller;

import com.bhukkad.cache.HomeFeedCacheService;
import com.bhukkad.config.ApiPaths;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.feed.PromoBannerService;
import com.bhukkad.membership.MembershipService;
import com.bhukkad.promotion.PromotionCampaignService;
import com.bhukkad.ratelimit.RateLimited;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Mobile-specific home feed endpoint that aggregates data for the mobile app launch.
 * <p>
 * This endpoint is analogous to {@link HomeFeedController#getHomeFeed()} but is
 * versioned under the {@code /mobile} path to allow for future mobile-specific
 * tailoring without affecting the general web home feed.
 */
@RestController
@RequestMapping(ApiPaths.V1_PREFIX + "/mobile")
@RequiredArgsConstructor
public class MobileFeedController {

    private final PromoBannerService promoBannerService;
    private final PromotionCampaignService promotionCampaignService;
    private final MembershipService membershipService;
    private final HomeFeedCacheService homeFeedCacheService;

    /**
     * Returns the composed mobile feed: promo banners, active promotion campaigns,
     * and membership plans. Each section is cached independently.
     *
     * <p>This endpoint is anonymous and non-personalised, and includes rate
     * limiting to protect against abuse.
     */
    @GetMapping("/feed")
    @RateLimited("mobile-feed")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMobileFeed() {
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "banners", homeFeedCacheService.getBanners(promoBannerService::listActive),
                "campaigns", homeFeedCacheService.getCampaigns(promotionCampaignService::listActive),
                "membershipPlans", homeFeedCacheService.getMembershipPlans(membershipService::listPlans))));
    }
}