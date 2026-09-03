package com.bhukkad.promotion;

import com.bhukkad.dto.request.PromotionCampaignRequest;
import com.bhukkad.dto.response.PromotionCampaignResponse;
import com.bhukkad.entity.MenuItem;
import com.bhukkad.entity.PromotionCampaign;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.repository.MenuItemRepository;
import com.bhukkad.repository.PromotionCampaignRepository;
import com.bhukkad.repository.RestaurantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromotionAdminServiceTest {

    @Mock
    private PromotionCampaignRepository promotionCampaignRepository;

    @Mock
    private RestaurantRepository restaurantRepository;

    @Mock
    private MenuItemRepository menuItemRepository;

    @InjectMocks
    private PromotionAdminService service;

    private PromotionCampaign campaign() {
        PromotionCampaign campaign = new PromotionCampaign();
        campaign.setId(1L);
        campaign.setName("Monsoon Sale");
        campaign.setCampaignType("PERCENTAGE");
        campaign.setDescription("Flat 20% off");
        campaign.setDiscountPercent(20.0);
        campaign.setFlatDiscountAmount(0.0);
        campaign.setMinOrderAmount(150.0);
        campaign.setMaxDiscountAmount(100.0);
        campaign.setFreeDelivery(true);
        campaign.setPriority(3);
        campaign.setUsageLimit(1000);
        campaign.setPerUserLimit(2);
        campaign.setIsActive(true);
        campaign.setStartsAt(LocalDateTime.of(2026, 8, 1, 0, 0));
        campaign.setEndsAt(LocalDateTime.of(2026, 8, 31, 23, 59));
        campaign.setBuyQuantity(2);
        campaign.setGetQuantity(1);
        campaign.setGetDiscountPercent(100.0);
        campaign.setTargetSegment(PromotionCampaign.CampaignSegment.VIP);

        Restaurant restaurant = new Restaurant();
        restaurant.setId(9L);
        campaign.setRestaurant(restaurant);

        MenuItem menuItem = new MenuItem();
        menuItem.setId(42L);
        campaign.setApplicableMenuItem(menuItem);
        return campaign;
    }

    private PromotionCampaignRequest fullRequest() {
        PromotionCampaignRequest request = new PromotionCampaignRequest();
        request.setName("Monsoon Sale");
        request.setCampaignType("PERCENTAGE");
        request.setDescription("Flat 20% off");
        request.setDiscountPercent(20.0);
        request.setFlatDiscountAmount(0.0);
        request.setMinOrderAmount(150.0);
        request.setMaxDiscountAmount(100.0);
        request.setRestaurantId(9L);
        request.setFreeDelivery(true);
        request.setPriority(3);
        request.setUsageLimit(1000);
        request.setPerUserLimit(2);
        request.setIsActive(true);
        request.setStartsAt(LocalDateTime.of(2026, 8, 1, 0, 0));
        request.setEndsAt(LocalDateTime.of(2026, 8, 31, 23, 59));
        request.setBuyQuantity(2);
        request.setGetQuantity(1);
        request.setGetDiscountPercent(100.0);
        request.setTargetSegment("VIP");
        request.setApplicableMenuItemId(42L);
        return request;
    }

    // ---------- listAll ----------

    @Test
    void listAll_noCampaigns_returnsEmptyList() {
        when(promotionCampaignRepository.findAll()).thenReturn(List.of());

        assertEquals(0, service.listAll().size());
    }

    @Test
    void listAll_mapsAllFields() {
        when(promotionCampaignRepository.findAll()).thenReturn(List.of(campaign()));

        PromotionCampaignResponse response = service.listAll().get(0);

        assertEquals(1L, response.getId());
        assertEquals("Monsoon Sale", response.getName());
        assertEquals("PERCENTAGE", response.getCampaignType());
        assertEquals("Flat 20% off", response.getDescription());
        assertEquals(20.0, response.getDiscountPercent());
        assertEquals(0.0, response.getFlatDiscountAmount());
        assertEquals(150.0, response.getMinOrderAmount());
        assertEquals(100.0, response.getMaxDiscountAmount());
        assertEquals(9L, response.getRestaurantId());
        assertEquals(true, response.getFreeDelivery());
        assertEquals(3, response.getPriority());
        assertEquals(1000, response.getUsageLimit());
        assertEquals(2, response.getPerUserLimit());
        assertEquals(true, response.getIsActive());
        assertEquals("2026-08-01T00:00", response.getStartsAt());
        assertEquals("2026-08-31T23:59", response.getEndsAt());
        assertEquals(2, response.getBuyQuantity());
        assertEquals(1, response.getGetQuantity());
        assertEquals(100.0, response.getGetDiscountPercent());
        assertEquals("VIP", response.getTargetSegment());
        assertEquals(42L, response.getApplicableMenuItemId());
    }

    @Test
    void listAll_nullAssociations_mapsToNulls() {
        PromotionCampaign bare = new PromotionCampaign();
        bare.setId(2L);
        bare.setName("Bare");
        bare.setCampaignType("FLAT");
        when(promotionCampaignRepository.findAll()).thenReturn(List.of(bare));

        PromotionCampaignResponse response = service.listAll().get(0);

        assertNull(response.getRestaurantId());
        assertNull(response.getStartsAt());
        assertNull(response.getEndsAt());
        assertNull(response.getTargetSegment());
        assertNull(response.getApplicableMenuItemId());
    }

    // ---------- create ----------

    @Test
    void create_success_mapsRequestAndSaves() {
        Restaurant restaurant = new Restaurant();
        restaurant.setId(9L);
        MenuItem menuItem = new MenuItem();
        menuItem.setId(42L);
        when(restaurantRepository.findById(9L)).thenReturn(Optional.of(restaurant));
        when(menuItemRepository.findById(42L)).thenReturn(Optional.of(menuItem));
        when(promotionCampaignRepository.save(any(PromotionCampaign.class))).thenAnswer(inv -> {
            PromotionCampaign c = inv.getArgument(0);
            c.setId(1L);
            return c;
        });

        PromotionCampaignResponse response = service.create(fullRequest());

        ArgumentCaptor<PromotionCampaign> captor = ArgumentCaptor.forClass(PromotionCampaign.class);
        verify(promotionCampaignRepository).save(captor.capture());
        PromotionCampaign saved = captor.getValue();
        assertEquals("Monsoon Sale", saved.getName());
        assertEquals("PERCENTAGE", saved.getCampaignType());
        assertEquals(20.0, saved.getDiscountPercent());
        assertEquals(150.0, saved.getMinOrderAmount());
        assertEquals(true, saved.getFreeDelivery());
        assertEquals(2, saved.getBuyQuantity());
        assertEquals(100.0, saved.getGetDiscountPercent());
        assertEquals(PromotionCampaign.CampaignSegment.VIP, saved.getTargetSegment());
        assertEquals(restaurant, saved.getRestaurant());
        assertEquals(menuItem, saved.getApplicableMenuItem());

        assertEquals(1L, response.getId());
        assertEquals(9L, response.getRestaurantId());
        assertEquals(42L, response.getApplicableMenuItemId());
    }

    @Test
    void create_nullRequestFields_areSkipped() {
        when(promotionCampaignRepository.save(any(PromotionCampaign.class))).thenAnswer(inv -> inv.getArgument(0));

        PromotionCampaignRequest request = new PromotionCampaignRequest();

        PromotionCampaignResponse response = service.create(request);

        assertNull(response.getName());
        assertNull(response.getCampaignType());
        assertNull(response.getRestaurantId());
        assertNull(response.getApplicableMenuItemId());
    }

    @Test
    void create_invalidTargetSegment_throws() {
        PromotionCampaignRequest request = new PromotionCampaignRequest();
        request.setTargetSegment("BOGUS");

        assertThrows(BusinessException.class, () -> service.create(request));
    }

    @Test
    void create_targetSegmentIsTrimmedAndUpperCased() {
        when(promotionCampaignRepository.save(any(PromotionCampaign.class))).thenAnswer(inv -> inv.getArgument(0));

        PromotionCampaignRequest request = new PromotionCampaignRequest();
        request.setTargetSegment("  new_user  ");

        PromotionCampaignResponse response = service.create(request);

        assertEquals("NEW_USER", response.getTargetSegment());
    }

    @Test
    void create_restaurantNotFound_throws() {
        PromotionCampaignRequest request = new PromotionCampaignRequest();
        request.setRestaurantId(9L);
        when(restaurantRepository.findById(9L)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> service.create(request));
        assertEquals("Restaurant not found", ex.getMessage());
    }

    @Test
    void create_menuItemNotFound_throws() {
        PromotionCampaignRequest request = new PromotionCampaignRequest();
        request.setApplicableMenuItemId(42L);
        when(menuItemRepository.findById(42L)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> service.create(request));
        assertEquals("Menu item not found", ex.getMessage());
    }

    // ---------- update ----------

    @Test
    void update_notFound_throws() {
        when(promotionCampaignRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.update(1L, fullRequest()));
    }

    @Test
    void update_success_appliesRequest() {
        PromotionCampaign campaign = campaign();
        when(promotionCampaignRepository.findById(1L)).thenReturn(Optional.of(campaign));
        when(promotionCampaignRepository.save(any(PromotionCampaign.class))).thenAnswer(inv -> inv.getArgument(0));

        PromotionCampaignRequest request = new PromotionCampaignRequest();
        request.setName("Renamed Campaign");
        request.setDiscountPercent(35.0);

        PromotionCampaignResponse response = service.update(1L, request);

        assertEquals("Renamed Campaign", response.getName());
        assertEquals(35.0, response.getDiscountPercent());
        assertEquals("Renamed Campaign", campaign.getName());
        verify(promotionCampaignRepository).save(campaign);
    }

    // ---------- deactivate ----------

    @Test
    void deactivate_notFound_throws() {
        when(promotionCampaignRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.deactivate(1L));
    }

    @Test
    void deactivate_success_setsInactive() {
        PromotionCampaign campaign = campaign();
        when(promotionCampaignRepository.findById(1L)).thenReturn(Optional.of(campaign));
        when(promotionCampaignRepository.save(any(PromotionCampaign.class))).thenAnswer(inv -> inv.getArgument(0));

        service.deactivate(1L);

        assertEquals(false, campaign.getIsActive());
        verify(promotionCampaignRepository).save(campaign);
    }
}
