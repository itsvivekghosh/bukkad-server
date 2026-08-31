package com.bhukkad.service;

import com.bhukkad.entity.InventoryAlert;
import com.bhukkad.entity.MenuItem;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.entity.RestaurantOwner;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.InventoryAlertRepository;
import com.bhukkad.repository.MenuItemRepository;
import com.bhukkad.repository.RestaurantRepository;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.util.NotificationHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InventoryAlertServiceTest {

    @Mock
    private InventoryAlertRepository alertRepository;

    @Mock
    private MenuItemRepository menuItemRepository;

    @Mock
    private RestaurantRepository restaurantRepository;

    @Mock
    private SecurityUtils securityUtils;

    @Mock
    private NotificationHelper notificationHelper;

    @InjectMocks
    private InventoryAlertService service;

    @Test
    void getAlertsByRestaurant_returnsUnacknowledgedAlerts() {
        InventoryAlert alert = new InventoryAlert();
        alert.setId(1L);
        alert.setAcknowledged(false);
        alert.setSent(true);

        Restaurant restaurant = new Restaurant();
        restaurant.setId(1L);
        restaurant.setName("Test Restaurant");
        alert.setRestaurant(restaurant);

        MenuItem menuItem = new MenuItem();
        menuItem.setId(1L);
        menuItem.setName("Test Item");
        alert.setMenuItem(menuItem);

        when(alertRepository.findByRestaurantIdAndAcknowledgedFalseAndSentTrue(1L))
                .thenReturn(List.of(alert));

        var result = service.getAlertsByRestaurant(1L);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).getId());
    }

    @Test
    void acknowledgeAlert_success() {
        InventoryAlert alert = new InventoryAlert();
        alert.setId(1L);
        alert.setAcknowledged(false);

        Restaurant restaurant = new Restaurant();
        restaurant.setId(1L);
        RestaurantOwner owner = new RestaurantOwner();
        owner.setId(1L);
        restaurant.setOwner(owner);
        alert.setRestaurant(restaurant);

        when(alertRepository.findById(1L)).thenReturn(Optional.of(alert));
        when(restaurantRepository.findById(1L)).thenReturn(Optional.of(restaurant));
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(alertRepository.save(alert)).thenReturn(alert);

        service.acknowledgeAlert(1L);

        assertTrue(alert.getAcknowledged());
        verify(alertRepository).save(alert);
    }

    @Test
    void acknowledgeAlert_notFound_throws() {
        when(alertRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.acknowledgeAlert(1L));
    }

    @Test
    void acknowledgeAlert_notOwner_throws() {
        InventoryAlert alert = new InventoryAlert();
        alert.setId(1L);

        Restaurant restaurant = new Restaurant();
        restaurant.setId(1L);
        RestaurantOwner owner = new RestaurantOwner();
        owner.setId(2L);
        restaurant.setOwner(owner);
        alert.setRestaurant(restaurant);

        when(alertRepository.findById(1L)).thenReturn(Optional.of(alert));
        when(restaurantRepository.findById(1L)).thenReturn(Optional.of(restaurant));
        when(securityUtils.getCurrentUserId()).thenReturn(1L);

        assertThrows(ResourceNotFoundException.class, () -> service.acknowledgeAlert(1L));
    }

    @Test
    void checkLowStockItems_sweepsAllRestaurantsInBatches() {
        // Batch A pagination: the sweep pages through the fleet instead of loading it whole.
        Restaurant r1 = new Restaurant();
        r1.setId(1L);
        Restaurant r2 = new Restaurant();
        r2.setId(2L);
        org.springframework.data.domain.Page<Restaurant> page1 =
                new org.springframework.data.domain.PageImpl<>(List.of(r1),
                        org.springframework.data.domain.PageRequest.of(0, 1), 2);
        org.springframework.data.domain.Page<Restaurant> page2 =
                new org.springframework.data.domain.PageImpl<>(List.of(r2),
                        org.springframework.data.domain.PageRequest.of(1, 1), 2);
        when(restaurantRepository.findAll(any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(page1).thenReturn(page2);
        when(menuItemRepository.findLowStockByRestaurant(1L, 10)).thenReturn(List.of());
        when(menuItemRepository.findLowStockByRestaurant(2L, 10)).thenReturn(List.of());

        service.checkLowStockItems();

        verify(menuItemRepository).findLowStockByRestaurant(1L, 10);
        verify(menuItemRepository).findLowStockByRestaurant(2L, 10);
    }

    @Test
    void createAlertIfNeeded_webhookConfigured_success_marksAlertSent() {
        MenuItem item = menuItemWithStock(1L, 11L, 0);
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "lowStockAlertWebhook", "https://hooks.example/alert");
        when(alertRepository.findTopByRestaurantIdAndMenuItemIdAndTypeOrderByCreatedAtDesc(
                1L, 11L, InventoryAlert.AlertType.OUT_OF_STOCK)).thenReturn(Optional.empty());

        service.createAlertIfNeeded(item);

        verify(notificationHelper).sendWebhookNotification(eq("https://hooks.example/alert"), contains("0 units"));
        org.mockito.ArgumentCaptor<InventoryAlert> captor =
                org.mockito.ArgumentCaptor.forClass(InventoryAlert.class);
        // Saved twice: once on creation, once after the notification marks it sent.
        verify(alertRepository, times(2)).save(captor.capture());
        assertTrue(captor.getAllValues().get(1).getSent());
        assertEquals(InventoryAlert.AlertType.OUT_OF_STOCK, captor.getValue().getType());
    }

    @Test
    void createAlertIfNeeded_webhookFailure_doesNotMarkAlertSent() {
        MenuItem item = menuItemWithStock(1L, 11L, 1); // < 20% of threshold → CRITICAL
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "lowStockAlertWebhook", "https://hooks.example/alert");
        when(alertRepository.findTopByRestaurantIdAndMenuItemIdAndTypeOrderByCreatedAtDesc(
                1L, 11L, InventoryAlert.AlertType.CRITICAL_STOCK)).thenReturn(Optional.empty());
        doThrow(new RuntimeException("hook down"))
                .when(notificationHelper).sendWebhookNotification(anyString(), anyString());

        assertDoesNotThrow(() -> service.createAlertIfNeeded(item));

        // Alert persisted on creation, but the notification failure must not
        // flip sent=true (the sweep would re-notify on the next cycle).
        org.mockito.ArgumentCaptor<InventoryAlert> captor =
                org.mockito.ArgumentCaptor.forClass(InventoryAlert.class);
        verify(alertRepository, times(1)).save(captor.capture());
        assertFalse(captor.getValue().getSent());
        assertEquals(InventoryAlert.AlertType.CRITICAL_STOCK, captor.getValue().getType());
    }

    @Test
    void acknowledgeAlert_restaurantLookupMissing_throws() {
        InventoryAlert alert = new InventoryAlert();
        alert.setId(1L);
        Restaurant restaurant = new Restaurant();
        restaurant.setId(9L);
        alert.setRestaurant(restaurant);

        when(alertRepository.findById(1L)).thenReturn(Optional.of(alert));
        when(restaurantRepository.findById(9L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.acknowledgeAlert(1L));
    }

    private MenuItem menuItemWithStock(Long restaurantId, Long itemId, Integer stock) {
        Restaurant restaurant = new Restaurant();
        restaurant.setId(restaurantId);
        restaurant.setName("Stock Restaurant");
        com.bhukkad.entity.MenuCategory category = new com.bhukkad.entity.MenuCategory();
        category.setRestaurant(restaurant);
        MenuItem item = new MenuItem();
        item.setId(itemId);
        item.setName("Paneer Tikka");
        item.setCategory(category);
        item.setStockQuantity(stock);
        return item;
    }
}