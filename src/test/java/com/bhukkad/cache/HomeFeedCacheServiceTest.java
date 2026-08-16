package com.bhukkad.cache;

import com.bhukkad.dto.response.MembershipPlanResponse;
import com.bhukkad.dto.response.PromoBannerResponse;
import com.bhukkad.dto.response.PromotionCampaignResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link HomeFeedCacheService}.
 *
 * <p>{@link RedisCacheService} is mocked, so these tests assert the caching
 * contract only: the correct key, the correct DTO type, the configured TTL and
 * the caller's loader are handed to Redis, and whatever Redis returns is passed
 * straight back. The read-through behaviour itself is covered by
 * {@code RedisCacheServiceTest}.
 *
 * <p>The {@code @Value} TTL field is populated with
 * {@link ReflectionTestUtils#setField} because no Spring context is started.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
class HomeFeedCacheServiceTest {

    @Mock
    private RedisCacheService cacheService;

    @InjectMocks
    private HomeFeedCacheService homeFeedCacheService;

    @Test
    void getBanners_usesBannerKeyTypeAndTtl() {
        ReflectionTestUtils.setField(homeFeedCacheService, "homeFeedTtlSeconds", 60L);
        List<PromoBannerResponse> banners = List.of(PromoBannerResponse.builder().id(1L).build());
        Supplier<List<PromoBannerResponse>> loader = () -> banners;
        when(cacheService.getListOrCompute(
                eq("home-feed:banners"), eq(PromoBannerResponse.class), eq(60L), any()))
                .thenReturn(banners);

        assertSame(banners, homeFeedCacheService.getBanners(loader));
        verify(cacheService).getListOrCompute("home-feed:banners", PromoBannerResponse.class, 60L, loader);
    }

    @Test
    void getCampaigns_usesCampaignKeyTypeAndTtl() {
        ReflectionTestUtils.setField(homeFeedCacheService, "homeFeedTtlSeconds", 30L);
        List<PromotionCampaignResponse> campaigns =
                List.of(PromotionCampaignResponse.builder().id(2L).build());
        when(cacheService.getListOrCompute(
                eq("home-feed:campaigns"), eq(PromotionCampaignResponse.class), eq(30L), any()))
                .thenReturn(campaigns);

        List<PromotionCampaignResponse> result = homeFeedCacheService.getCampaigns(() -> campaigns);

        assertEquals(1, result.size());
        assertEquals(2L, result.get(0).getId());
    }

    @Test
    void getMembershipPlans_usesPlanKeyTypeAndTtl() {
        ReflectionTestUtils.setField(homeFeedCacheService, "homeFeedTtlSeconds", 120L);
        List<MembershipPlanResponse> plans = List.of(MembershipPlanResponse.builder().id(3L).build());
        when(cacheService.getListOrCompute(
                eq("home-feed:membership-plans"), eq(MembershipPlanResponse.class), eq(120L), any()))
                .thenReturn(plans);

        assertSame(plans, homeFeedCacheService.getMembershipPlans(() -> plans));
    }

    @Test
    void getBanners_doesNotInvokeLoaderItself() {
        ReflectionTestUtils.setField(homeFeedCacheService, "homeFeedTtlSeconds", 60L);
        when(cacheService.getListOrCompute(
                eq("home-feed:banners"), eq(PromoBannerResponse.class), eq(60L), any()))
                .thenReturn(List.of());

        // The loader must be handed to Redis untouched; only a cache miss may run it.
        homeFeedCacheService.getBanners(() -> {
            throw new AssertionError("loader must not be invoked by the cache service");
        });
    }

    @Test
    void invalidateHomeFeed_deletesAllThreeKeys() {
        homeFeedCacheService.invalidateHomeFeed();

        verify(cacheService).delete("home-feed:banners");
        verify(cacheService).delete("home-feed:campaigns");
        verify(cacheService).delete("home-feed:membership-plans");
        verify(cacheService, never()).deletePattern(any());
    }
}
