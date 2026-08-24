package com.bhukkad.inventory;

import com.bhukkad.dto.response.InventoryAlertResponse;
import com.bhukkad.entity.InventoryAlert;
import com.bhukkad.entity.MenuCategory;
import com.bhukkad.entity.MenuItem;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.entity.RestaurantOwner;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.InventoryAlertRepository;
import com.bhukkad.repository.MenuItemRepository;
import com.bhukkad.repository.RestaurantRepository;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.service.InventoryAlertService;
import com.bhukkad.util.NotificationHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link InventoryAlertService} — low-stock thresholding,
 * alert deduplication (4h window), and restaurant-owner acknowledgement.
 */
@ExtendWith(MockitoExtension.class)
class InventoryAlertServiceTest {

    @Mock
    private InventoryAlertRepository inventoryAlertRepository;
    @Mock
    private MenuItemRepository menuItemRepository;
    @Mock
    private RestaurantRepository restaurantRepository;
    @Mock
    private SecurityUtils securityUtils;
    @Mock
    private NotificationHelper notificationHelper;

    private InventoryAlertService service;

    @BeforeEach
    void setUp() {
        service = new InventoryAlertService(inventoryAlertRepository, menuItemRepository,
                restaurantRepository, securityUtils, notificationHelper);
        // Disable webhook for tests (avoids network calls)
        ReflectionTestUtils.setField(service, "lowStockAlertWebhook", "");
    }

    private Restaurant restaurant(Long id, RestaurantOwner owner) {
        Restaurant r = new Restaurant();
        r.setId(id);
        r.setName("Test Restaurant");
        r.setOwner(owner);
        return r;
    }

    private RestaurantOwner owner(Long id) {
        RestaurantOwner u = new RestaurantOwner();
        u.setId(id);
        return u;
    }

    private MenuItem menuItem(Long id, Restaurant r, Integer stock) {
        MenuCategory category = new MenuCategory();
        category.setRestaurant(r);
        MenuItem m = new MenuItem();
        m.setId(id);
        m.setName("Item " + id);
        m.setCategory(category);
        m.setStockQuantity(stock);
        return m;
    }

    @Test
    void createAlertIfNeeded_healthyStock_skips() {
        MenuItem item = menuItem(1L, restaurant(1L, owner(9L)), 50);

        service.createAlertIfNeeded(item);

        verify(inventoryAlertRepository, never()).save(any());
    }

    @Test
    void createAlertIfNeeded_outOfStock_createsAlert() {
        Restaurant r = restaurant(1L, owner(9L));
        MenuItem item = menuItem(1L, r, 0);

        when(inventoryAlertRepository
                .findTopByRestaurantIdAndMenuItemIdAndTypeOrderByCreatedAtDesc(1L, 1L, InventoryAlert.AlertType.OUT_OF_STOCK))
                .thenReturn(Optional.empty());
        when(inventoryAlertRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.createAlertIfNeeded(item);

        // save() is invoked twice: once for the alert row, once after marking sent=true
        verify(inventoryAlertRepository, org.mockito.Mockito.atLeastOnce()).save(any());
    }

    @Test
    void createAlertIfNeeded_criticalStock_classifiesCorrectly() {
        Restaurant r = restaurant(1L, owner(9L));
        MenuItem item = menuItem(1L, r, 1); // < 20% of 10

        when(inventoryAlertRepository
                .findTopByRestaurantIdAndMenuItemIdAndTypeOrderByCreatedAtDesc(1L, 1L, InventoryAlert.AlertType.CRITICAL_STOCK))
                .thenReturn(Optional.empty());
        when(inventoryAlertRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.createAlertIfNeeded(item);

        verify(inventoryAlertRepository, org.mockito.Mockito.atLeastOnce()).save(any());
    }

    @Test
    void createAlertIfNeeded_lowStock_classifiesCorrectly() {
        Restaurant r = restaurant(1L, owner(9L));
        MenuItem item = menuItem(1L, r, 5); // >= 20% but <= 10

        when(inventoryAlertRepository
                .findTopByRestaurantIdAndMenuItemIdAndTypeOrderByCreatedAtDesc(1L, 1L, InventoryAlert.AlertType.LOW_STOCK))
                .thenReturn(Optional.empty());
        when(inventoryAlertRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.createAlertIfNeeded(item);

        verify(inventoryAlertRepository, org.mockito.Mockito.atLeastOnce()).save(any());
    }

    @Test
    void createAlertIfNeeded_recentAlertWithinWindow_skips() {
        Restaurant r = restaurant(1L, owner(9L));
        MenuItem item = menuItem(1L, r, 3);

        InventoryAlert recent = new InventoryAlert();
        recent.setCreatedAt(LocalDateTime.now().minusHours(1)); // within 4h window
        when(inventoryAlertRepository
                .findTopByRestaurantIdAndMenuItemIdAndTypeOrderByCreatedAtDesc(1L, 1L, InventoryAlert.AlertType.LOW_STOCK))
                .thenReturn(Optional.of(recent));

        service.createAlertIfNeeded(item);

        verify(inventoryAlertRepository, never()).save(any());
    }

    @Test
    void getAlertsByRestaurant_mapsResponse() {
        Restaurant r = restaurant(1L, owner(9L));
        MenuItem item = menuItem(1L, r, 2);

        InventoryAlert alert = new InventoryAlert();
        alert.setId(5L);
        alert.setRestaurant(r);
        alert.setMenuItem(item);
        alert.setType(InventoryAlert.AlertType.LOW_STOCK);
        alert.setCurrentStock(2);
        alert.setThreshold(10);
        alert.setAcknowledged(false);
        alert.setSent(true);
        alert.setCreatedAt(LocalDateTime.of(2026, 8, 22, 12, 0));

        when(inventoryAlertRepository.findByRestaurantIdAndAcknowledgedFalseAndSentTrue(1L))
                .thenReturn(List.of(alert));

        List<InventoryAlertResponse> alerts = service.getAlertsByRestaurant(1L);

        assertEquals(1, alerts.size());
        assertEquals(5L, alerts.get(0).getId());
        assertEquals("Test Restaurant", alerts.get(0).getRestaurantName());
        assertEquals("Item 1", alerts.get(0).getMenuItemName());
        assertEquals(InventoryAlert.AlertType.LOW_STOCK, alerts.get(0).getType());
        assertEquals(2, alerts.get(0).getCurrentStock());
    }

    @Test
    void acknowledgeAlert_marksAcknowledged() {
        Restaurant r = restaurant(1L, owner(9L));
        InventoryAlert alert = new InventoryAlert();
        alert.setId(5L);
        alert.setRestaurant(r);
        alert.setAcknowledged(false);

        when(inventoryAlertRepository.findById(5L)).thenReturn(Optional.of(alert));
        when(restaurantRepository.findById(1L)).thenReturn(Optional.of(r));
        when(securityUtils.getCurrentUserId()).thenReturn(9L);
        when(inventoryAlertRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.acknowledgeAlert(5L);

        assertTrue(alert.getAcknowledged());
        verify(inventoryAlertRepository).save(alert);
    }

    @Test
    void acknowledgeAlert_notOwner_throws() {
        Restaurant r = restaurant(1L, owner(9L));
        InventoryAlert alert = new InventoryAlert();
        alert.setId(5L);
        alert.setRestaurant(r);

        when(inventoryAlertRepository.findById(5L)).thenReturn(Optional.of(alert));
        when(restaurantRepository.findById(1L)).thenReturn(Optional.of(r));
        when(securityUtils.getCurrentUserId()).thenReturn(999L); // different owner

        assertThrows(ResourceNotFoundException.class, () -> service.acknowledgeAlert(5L));
    }

    @Test
    void acknowledgeAlert_notFound_throws() {
        when(inventoryAlertRepository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.acknowledgeAlert(99L));
    }
}
