package com.bhukkad.promotion;

import com.bhukkad.entity.CartItem;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.MenuItem;
import com.bhukkad.entity.PromotionCampaign;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.repository.CampaignUsageRepository;
import com.bhukkad.repository.PromotionCampaignRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromotionEngineServiceTest {

    @Mock
    private PromotionCampaignRepository promotionCampaignRepository;
    @Mock
    private CampaignUsageRepository campaignUsageRepository;

    @InjectMocks
    private PromotionEngineService service;

    private Restaurant restaurant() {
        Restaurant restaurant = new Restaurant();
        restaurant.setId(1L);
        return restaurant;
    }

    private Customer customer(int ageDays, int loyaltyPoints) {
        Customer customer = new Customer();
        customer.setId(7L);
        customer.setLoyaltyPoints(loyaltyPoints);
        customer.setCreatedAt(LocalDateTime.now().minusDays(ageDays));
        return customer;
    }

    private PromotionCampaign percentCampaign(String name, double percent, Double minOrder) {
        PromotionCampaign campaign = new PromotionCampaign();
        campaign.setId(1L);
        campaign.setName(name);
        campaign.setDiscountPercent(percent);
        campaign.setMinOrderAmount(minOrder);
        campaign.setPerUserLimit(10);
        return campaign;
    }

    private CartItem cartItem(long menuItemId, double price, int quantity) {
        MenuItem menuItem = new MenuItem();
        menuItem.setId(menuItemId);
        menuItem.setPrice(price);
        CartItem item = new CartItem();
        item.setMenuItem(menuItem);
        item.setQuantity(quantity);
        return item;
    }

    @Test
    void noActiveCampaigns_returnsNoDiscount() {
        when(promotionCampaignRepository.findActiveCampaigns(org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                .thenReturn(List.of());

        var result = service.evaluateBestDiscount(customer(1, 0), restaurant(), 1000.0);

        assertNull(result.campaign());
        assertEquals(0.0, result.discountAmount());
        assertFalse(result.freeDelivery());
    }

    @Test
    void percentDiscountBelowMinOrder_notEligible() {
        PromotionCampaign campaign = percentCampaign("Flat 20%", 20.0, 500.0);
        when(promotionCampaignRepository.findActiveCampaigns(org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                .thenReturn(List.of(campaign));

        var result = service.evaluateBestDiscount(customer(1, 0), restaurant(), 300.0);

        assertNull(result.campaign());
        assertEquals(0.0, result.discountAmount());
    }

    @Test
    void percentDiscountAppliesWithMaxCap() {
        PromotionCampaign campaign = percentCampaign("Flat 20%", 20.0, null);
        campaign.setMaxDiscountAmount(100.0);
        when(promotionCampaignRepository.findActiveCampaigns(org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                .thenReturn(List.of(campaign));

        var result = service.evaluateBestDiscount(customer(1, 0), restaurant(), 1000.0);

        assertEquals(100.0, result.discountAmount()); // 200 capped at 100
    }

    @Test
    void restaurantBoundCampaign_onlyAppliesToTargetRestaurant() {
        PromotionCampaign campaign = percentCampaign("R1 only", 10.0, null);
        campaign.setRestaurant(restaurant());
        when(promotionCampaignRepository.findActiveCampaigns(org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                .thenReturn(List.of(campaign));

        Restaurant other = new Restaurant();
        other.setId(2L);

        var notEligible = service.evaluateBestDiscount(customer(1, 0), other, 1000.0);
        var eligible = service.evaluateBestDiscount(customer(1, 0), restaurant(), 1000.0);

        assertNull(notEligible.campaign());
        assertEquals(100.0, eligible.discountAmount());
    }

    @Test
    void newUserSegment_onlyAppliesToRecentCustomers() {
        PromotionCampaign campaign = percentCampaign("Welcome", 10.0, null);
        campaign.setTargetSegment(PromotionCampaign.CampaignSegment.NEW_USER);
        when(promotionCampaignRepository.findActiveCampaigns(org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                .thenReturn(List.of(campaign));

        var newUser = service.evaluateBestDiscount(customer(5, 0), restaurant(), 1000.0);
        var oldUser = service.evaluateBestDiscount(customer(200, 0), restaurant(), 1000.0);

        assertEquals(100.0, newUser.discountAmount());
        assertEquals(0.0, oldUser.discountAmount());
    }

    @Test
    void vipSegment_onlyAppliesToHighSpenders() {
        PromotionCampaign campaign = percentCampaign("VIP", 15.0, null);
        campaign.setTargetSegment(PromotionCampaign.CampaignSegment.VIP);
        when(promotionCampaignRepository.findActiveCampaigns(org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                .thenReturn(List.of(campaign));

        var vip = service.evaluateBestDiscount(customer(1, 1500), restaurant(), 1000.0);
        var nonVip = service.evaluateBestDiscount(customer(1, 100), restaurant(), 1000.0);

        assertEquals(150.0, vip.discountAmount());
        assertEquals(0.0, nonVip.discountAmount());
    }

    @Test
    void buyXGetY_freeUnitsDiscounted() {
        PromotionCampaign campaign = percentCampaign("Buy 2 Get 1 Free", 0.0, null);
        campaign.setBuyQuantity(2);
        campaign.setGetQuantity(1);
        campaign.setGetDiscountPercent(100.0); // free
        when(promotionCampaignRepository.findActiveCampaigns(org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                .thenReturn(List.of(campaign));

        var cart = List.of(cartItem(11L, 100.0, 3));
        var result = service.evaluateBestDiscount(customer(1, 0), restaurant(), 300.0, cart);

        assertEquals(100.0, result.discountAmount()); // 1 free unit at ₹100
    }

    @Test
    void buyXGetY_partialDiscountOnGetUnits() {
        PromotionCampaign campaign = percentCampaign("Buy 2 Get 1 at 50%", 0.0, null);
        campaign.setBuyQuantity(2);
        campaign.setGetQuantity(1);
        campaign.setGetDiscountPercent(50.0);
        when(promotionCampaignRepository.findActiveCampaigns(org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                .thenReturn(List.of(campaign));

        var cart = List.of(cartItem(11L, 100.0, 3));
        var result = service.evaluateBestDiscount(customer(1, 0), restaurant(), 300.0, cart);

        assertEquals(50.0, result.discountAmount());
    }

    @Test
    void buyXGetY_quantityBelowBuyThreshold_notEligible() {
        PromotionCampaign campaign = percentCampaign("Buy 2 Get 1 Free", 0.0, null);
        campaign.setBuyQuantity(2);
        campaign.setGetQuantity(1);
        campaign.setGetDiscountPercent(100.0);
        when(promotionCampaignRepository.findActiveCampaigns(org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                .thenReturn(List.of(campaign));

        var cart = List.of(cartItem(11L, 100.0, 1));
        var result = service.evaluateBestDiscount(customer(1, 0), restaurant(), 100.0, cart);

        assertEquals(0.0, result.discountAmount());
    }

    @Test
    void buyXGetY_applicableMenuItemOnly_countsThatItem() {
        MenuItem target = new MenuItem();
        target.setId(11L);
        target.setPrice(100.0);

        PromotionCampaign campaign = percentCampaign("Buy 2 Get 1 Free", 0.0, null);
        campaign.setBuyQuantity(2);
        campaign.setGetQuantity(1);
        campaign.setGetDiscountPercent(100.0);
        campaign.setApplicableMenuItem(target);
        when(promotionCampaignRepository.findActiveCampaigns(org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                .thenReturn(List.of(campaign));

        var cart = List.of(cartItem(11L, 100.0, 3), cartItem(99L, 500.0, 5));
        var result = service.evaluateBestDiscount(customer(1, 0), restaurant(), 2800.0, cart);

        assertEquals(100.0, result.discountAmount()); // only item 11 counts
    }

    @Test
    void usageLimitReached_notEligible() {
        PromotionCampaign campaign = percentCampaign("Limited", 10.0, null);
        campaign.setUsageLimit(5);
        when(promotionCampaignRepository.findActiveCampaigns(org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                .thenReturn(List.of(campaign));
        when(campaignUsageRepository.countByCampaignId(1L)).thenReturn(5L);

        var result = service.evaluateBestDiscount(customer(1, 0), restaurant(), 1000.0);

        assertNull(result.campaign());
    }

    @Test
    void picksBestDiscountAmongEligibleCampaigns() {
        PromotionCampaign small = percentCampaign("10%", 10.0, null);
        small.setId(1L);
        PromotionCampaign large = percentCampaign("25%", 25.0, null);
        large.setId(2L);
        when(promotionCampaignRepository.findActiveCampaigns(org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                .thenReturn(List.of(small, large));

        var result = service.evaluateBestDiscount(customer(1, 0), restaurant(), 1000.0);

        assertEquals(250.0, result.discountAmount());
        assertEquals("25%", result.campaign().getName());
    }

    @Test
    void freeDeliveryCampaign_setsFlag() {
        PromotionCampaign campaign = percentCampaign("Free Delivery", 0.0, null);
        campaign.setFreeDelivery(true);
        when(promotionCampaignRepository.findActiveCampaigns(org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                .thenReturn(List.of(campaign));

        var result = service.evaluateBestDiscount(customer(1, 0), restaurant(), 1000.0);

        assertTrue(result.freeDelivery());
    }
}
