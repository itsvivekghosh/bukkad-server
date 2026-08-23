package com.bhukkad.promotion;

import com.bhukkad.dto.response.PromotionCampaignResponse;
import com.bhukkad.entity.PromotionCampaign;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.entity.MenuItem;
import com.bhukkad.repository.PromotionCampaignRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PromotionCampaignServiceTest {

    @Mock
    private PromotionCampaignRepository promotionCampaignRepository;

    private PromotionCampaignService service;

    @BeforeEach
    void setUp() {
        service = new PromotionCampaignService(promotionCampaignRepository);
    }

    @Test
    void listActive_returnsMappedCampaigns() {
        PromotionCampaign campaign = createCampaign(1L, "Test Campaign", 10.0);
        when(promotionCampaignRepository.findActiveCampaigns(any(LocalDateTime.class)))
                .thenReturn(List.of(campaign));

        List<PromotionCampaignResponse> result = service.listActive();

        assertEquals(1, result.size());
        assertEquals("Test Campaign", result.get(0).getName());
        assertEquals(10.0, result.get(0).getDiscountPercent());
    }

    @Test
    void listActive_returnsEmptyList_whenNoCampaigns() {
        when(promotionCampaignRepository.findActiveCampaigns(any(LocalDateTime.class)))
                .thenReturn(List.of());

        List<PromotionCampaignResponse> result = service.listActive();

        assertTrue(result.isEmpty());
    }

    @Test
    void getBestDiscount_returnsDiscount_whenCampaignApplies() {
        PromotionCampaign campaign = createCampaign(1L, "Test", 20.0);
        campaign.setMinOrderAmount(100.0);
        when(promotionCampaignRepository.findActiveCampaigns(any(LocalDateTime.class)))
                .thenReturn(List.of(campaign));

        double discount = service.getBestDiscount(200.0);

        assertEquals(40.0, discount); // 20% of 200 = 40
    }

    @Test
    void getBestDiscount_returnsZero_whenNoCampaigns() {
        when(promotionCampaignRepository.findActiveCampaigns(any(LocalDateTime.class)))
                .thenReturn(List.of());

        double discount = service.getBestDiscount(200.0);

        assertEquals(0.0, discount);
    }

    @Test
    void getBestDiscount_returnsZero_whenCampaignBelowMinOrder() {
        PromotionCampaign campaign = createCampaign(1L, "Test", 20.0);
        campaign.setMinOrderAmount(500.0);
        when(promotionCampaignRepository.findActiveCampaigns(any(LocalDateTime.class)))
                .thenReturn(List.of(campaign));

        double discount = service.getBestDiscount(200.0);

        assertEquals(0.0, discount);
    }

    @Test
    void getBestDiscount_returnsZero_whenCampaignHasNoDiscount() {
        PromotionCampaign campaign = createCampaign(1L, "Test", 0.0);
        campaign.setMinOrderAmount(100.0);
        when(promotionCampaignRepository.findActiveCampaigns(any(LocalDateTime.class)))
                .thenReturn(List.of(campaign));

        double discount = service.getBestDiscount(200.0);

        assertEquals(0.0, discount);
    }

    @Test
    void getBestDiscount_returnsZero_whenCampaignDiscountNull() {
        PromotionCampaign campaign = createCampaign(1L, "Test", null);
        campaign.setMinOrderAmount(100.0);
        when(promotionCampaignRepository.findActiveCampaigns(any(LocalDateTime.class)))
                .thenReturn(List.of(campaign));

        double discount = service.getBestDiscount(200.0);

        assertEquals(0.0, discount);
    }

    @Test
    void getBestDiscount_picksFirstMatchingCampaign() {
        PromotionCampaign campaign1 = createCampaign(1L, "First", 10.0);
        campaign1.setMinOrderAmount(50.0);
        PromotionCampaign campaign2 = createCampaign(2L, "Second", 20.0);
        campaign2.setMinOrderAmount(50.0);
        when(promotionCampaignRepository.findActiveCampaigns(any(LocalDateTime.class)))
                .thenReturn(List.of(campaign1, campaign2));

        double discount = service.getBestDiscount(200.0);

        assertEquals(20.0, discount); // First campaign's discount (10% of 200 = 20)
    }

    @Test
    void toResponse_mapsAllFields() {
        Restaurant restaurant = new Restaurant();
        restaurant.setId(5L);

        MenuItem menuItem = new MenuItem();
        menuItem.setId(10L);

        PromotionCampaign campaign = createCampaign(1L, "Full Campaign", 15.0);
        campaign.setCampaignType("FLAT");
        campaign.setDescription("Description");
        campaign.setFlatDiscountAmount(25.0);
        campaign.setMaxDiscountAmount(100.0);
        campaign.setRestaurant(restaurant);
        campaign.setFreeDelivery(true);
        campaign.setPriority(1);
        campaign.setIsActive(true);
        campaign.setStartsAt(LocalDateTime.now().minusDays(1));
        campaign.setEndsAt(LocalDateTime.now().plusDays(1));
        campaign.setBuyQuantity(2);
        campaign.setGetQuantity(1);
        campaign.setGetDiscountPercent(50.0);
        campaign.setTargetSegment(PromotionCampaign.CampaignSegment.NEW_USER);
        campaign.setApplicableMenuItem(menuItem);

        // Test by calling listActive which internally uses toResponse
        when(promotionCampaignRepository.findActiveCampaigns(any(LocalDateTime.class)))
                .thenReturn(List.of(campaign));

        List<PromotionCampaignResponse> result = service.listActive();

        assertEquals(1, result.size());
        PromotionCampaignResponse response = result.get(0);
        assertEquals(1L, response.getId());
        assertEquals("Full Campaign", response.getName());
        assertEquals("FLAT", response.getCampaignType());
        assertEquals("Description", response.getDescription());
        assertEquals(15.0, response.getDiscountPercent());
        assertEquals(25.0, response.getFlatDiscountAmount());
        assertEquals(50.0, response.getMinOrderAmount());
        assertEquals(100.0, response.getMaxDiscountAmount());
        assertEquals(5L, response.getRestaurantId());
        assertTrue(response.getFreeDelivery());
        assertEquals(1, response.getPriority());
        assertTrue(response.getIsActive());
        assertNotNull(response.getStartsAt());
        assertNotNull(response.getEndsAt());
        assertEquals(2, response.getBuyQuantity());
        assertEquals(1, response.getGetQuantity());
        assertEquals(50.0, response.getGetDiscountPercent());
        assertEquals(PromotionCampaign.CampaignSegment.NEW_USER.name(), response.getTargetSegment());
        assertEquals(10L, response.getApplicableMenuItemId());
    }

    private PromotionCampaign createCampaign(Long id, String name, Double discountPercent) {
        PromotionCampaign campaign = new PromotionCampaign();
        campaign.setId(id);
        campaign.setName(name);
        campaign.setDiscountPercent(discountPercent);
        campaign.setIsActive(true);
        campaign.setMinOrderAmount(50.0);
        return campaign;
    }
}