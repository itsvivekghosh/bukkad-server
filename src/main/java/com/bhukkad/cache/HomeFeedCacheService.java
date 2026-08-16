package com.bhukkad.cache;

import com.bhukkad.dto.response.MembershipPlanResponse;
import com.bhukkad.dto.response.PromoBannerResponse;
import com.bhukkad.dto.response.PromotionCampaignResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Supplier;

/**
 * Read-through cache for the customer home feed.
 *
 * <p>The home feed is the highest-traffic anonymous read in the app: every app
 * launch fetches banners, active promotion campaigns and the membership plan
 * catalogue. None of those three lists are personalised, so a single global
 * cache entry per list serves all callers.
 *
 * <p>Each list is cached under its own key rather than caching the composed
 * response map. That way the dedicated {@code /home/banners},
 * {@code /home/campaigns} and {@code /home/membership-plans} endpoints share
 * entries with the composed {@code /home/feed} endpoint instead of duplicating
 * them, and a change to one list does not force the others to be recomputed.
 *
 * <p>Caching uses {@link RedisCacheService#getListOrCompute} so concurrent
 * misses collapse onto a single database read (Redis lock based stampede
 * protection) and results are back-filled into the L1 Caffeine cache. All
 * cache operations swallow their own failures, so a Redis outage degrades to
 * direct database reads rather than errors.
 */
@Service
@RequiredArgsConstructor
public class HomeFeedCacheService {

    private final RedisCacheService cacheService;

    /** Short TTL: promo/campaign edits by ops must become visible within a minute. */
    @Value("${cache.ttl.home-feed:60}")
    private long homeFeedTtlSeconds;

    /**
     * Returns the active promo banners, computing them via {@code loader} on a miss.
     *
     * @param loader supplier that reads the banners from the database
     * @return cached or freshly loaded banners, never {@code null}
     */
    public List<PromoBannerResponse> getBanners(Supplier<List<PromoBannerResponse>> loader) {
        return cacheService.getListOrCompute(
                CacheKeyGenerator.homeFeedBanners(),
                PromoBannerResponse.class,
                homeFeedTtlSeconds,
                loader);
    }

    /**
     * Returns the active promotion campaigns, computing them via {@code loader} on a miss.
     *
     * @param loader supplier that reads the campaigns from the database
     * @return cached or freshly loaded campaigns, never {@code null}
     */
    public List<PromotionCampaignResponse> getCampaigns(Supplier<List<PromotionCampaignResponse>> loader) {
        return cacheService.getListOrCompute(
                CacheKeyGenerator.homeFeedCampaigns(),
                PromotionCampaignResponse.class,
                homeFeedTtlSeconds,
                loader);
    }

    /**
     * Returns the active membership plans, computing them via {@code loader} on a miss.
     *
     * @param loader supplier that reads the plans from the database
     * @return cached or freshly loaded plans, never {@code null}
     */
    public List<MembershipPlanResponse> getMembershipPlans(Supplier<List<MembershipPlanResponse>> loader) {
        return cacheService.getListOrCompute(
                CacheKeyGenerator.homeFeedMembershipPlans(),
                MembershipPlanResponse.class,
                homeFeedTtlSeconds,
                loader);
    }

    /**
     * Drops every cached home feed list.
     *
     * <p>Intended for admin tooling that mutates banners, campaigns or plans and
     * wants the change reflected before the TTL expires.
     */
    public void invalidateHomeFeed() {
        cacheService.delete(CacheKeyGenerator.homeFeedBanners());
        cacheService.delete(CacheKeyGenerator.homeFeedCampaigns());
        cacheService.delete(CacheKeyGenerator.homeFeedMembershipPlans());
    }
}
