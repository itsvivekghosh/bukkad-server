package com.bhukkad.controller;

import com.bhukkad.cache.HomeFeedCacheService;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.MembershipPlanResponse;
import com.bhukkad.dto.response.PromoBannerResponse;
import com.bhukkad.dto.response.PromotionCampaignResponse;
import com.bhukkad.feed.PromoBannerService;
import com.bhukkad.membership.MembershipService;
import com.bhukkad.promotion.PromotionCampaignService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HomeFeedControllerTest {

    @Mock
    private PromoBannerService promoBannerService;
    @Mock
    private PromotionCampaignService promotionCampaignService;
    @Mock
    private MembershipService membershipService;
    @Mock
    private HomeFeedCacheService homeFeedCacheService;

    @InjectMocks
    private HomeFeedController controller;

    @Test
    void getBanners_servedFromCache() {
        List<PromoBannerResponse> banners = List.of(PromoBannerResponse.builder().build());
        when(homeFeedCacheService.getBanners(any(Supplier.class))).thenReturn(banners);

        ResponseEntity<ApiResponse<List<PromoBannerResponse>>> response = controller.getBanners();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(banners, response.getBody().getData());
        verify(homeFeedCacheService).getBanners(any(Supplier.class));
    }

    @Test
    void getCampaigns_servedFromCache() {
        List<PromotionCampaignResponse> campaigns = List.of(PromotionCampaignResponse.builder().build());
        when(homeFeedCacheService.getCampaigns(any(Supplier.class))).thenReturn(campaigns);

        ResponseEntity<ApiResponse<List<PromotionCampaignResponse>>> response = controller.getCampaigns();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(campaigns, response.getBody().getData());
        verify(homeFeedCacheService).getCampaigns(any(Supplier.class));
    }

    @Test
    void getHomeFeed_composesAllSections() {
        List<PromoBannerResponse> banners = List.of(PromoBannerResponse.builder().build());
        List<PromotionCampaignResponse> campaigns = List.of(PromotionCampaignResponse.builder().build());
        List<MembershipPlanResponse> plans = List.of(MembershipPlanResponse.builder().build());
        when(homeFeedCacheService.getBanners(any(Supplier.class))).thenReturn(banners);
        when(homeFeedCacheService.getCampaigns(any(Supplier.class))).thenReturn(campaigns);
        when(homeFeedCacheService.getMembershipPlans(any(Supplier.class))).thenReturn(plans);

        ResponseEntity<ApiResponse<Map<String, Object>>> response = controller.getHomeFeed();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> data = response.getBody().getData();
        assertSame(banners, data.get("banners"));
        assertSame(campaigns, data.get("campaigns"));
        assertSame(plans, data.get("membershipPlans"));
    }

    @Test
    void getMembershipPlans_servedFromCache() {
        List<MembershipPlanResponse> plans = List.of(MembershipPlanResponse.builder().build());
        when(homeFeedCacheService.getMembershipPlans(any(Supplier.class))).thenReturn(plans);

        ResponseEntity<ApiResponse<List<MembershipPlanResponse>>> response = controller.getMembershipPlans();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(plans, response.getBody().getData());
        verify(homeFeedCacheService).getMembershipPlans(any(Supplier.class));
    }
}
