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
}