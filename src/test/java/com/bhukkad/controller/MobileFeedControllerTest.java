package com.bhukkad.controller;

import com.bhukkad.cache.HomeFeedCacheService;
import com.bhukkad.dto.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MobileFeedControllerTest {

    @Mock
    private HomeFeedCacheService homeFeedCacheService;

    @Mock
    private com.bhukkad.feed.PromoBannerService promoBannerService;

    @Mock
    private com.bhukkad.promotion.PromotionCampaignService promotionCampaignService;

    @Mock
    private com.bhukkad.membership.MembershipService membershipService;

    @InjectMocks
    private MobileFeedController controller;

    @Test
    void getMobileFeed_returnsAllSections() {
        when(homeFeedCacheService.getBanners(any())).thenReturn(List.of());
        when(homeFeedCacheService.getCampaigns(any())).thenReturn(List.of());
        when(homeFeedCacheService.getMembershipPlans(any())).thenReturn(List.of());

        ResponseEntity<ApiResponse<Map<String, Object>>> response = controller.getMobileFeed();

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().isSuccess());
        Map<String, Object> data = response.getBody().getData();
        assertNotNull(data);
        assertTrue(data.containsKey("banners"));
        assertTrue(data.containsKey("campaigns"));
        assertTrue(data.containsKey("membershipPlans"));
    }

    @Test
    void getMobileFeed_returnsEmptyBanners() {
        when(homeFeedCacheService.getBanners(any())).thenReturn(List.of());
        when(homeFeedCacheService.getCampaigns(any())).thenReturn(List.of());
        when(homeFeedCacheService.getMembershipPlans(any())).thenReturn(List.of());

        ResponseEntity<ApiResponse<Map<String, Object>>> response = controller.getMobileFeed();
        Map<String, Object> data = response.getBody().getData();
        assertTrue(((List<?>) data.get("banners")).isEmpty());
    }
}