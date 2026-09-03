package com.bhukkad.restaurant;

import com.bhukkad.dto.request.RestaurantBusyModeRequest;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.repository.RestaurantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RestaurantBusyServiceTest {

    @Mock
    private RestaurantRepository restaurantRepository;

    @InjectMocks
    private RestaurantBusyService service;

    private Restaurant restaurant;
    private RestaurantBusyModeRequest request;

    @BeforeEach
    void setUp() {
        restaurant = new Restaurant();
        restaurant.setId(1L);
        restaurant.setBusyMode(false);
        restaurant.setBusyUntil(null);
        restaurant.setExtraPrepMinutes(0);

        request = new RestaurantBusyModeRequest();
        request.setBusyUntil(LocalDateTime.now().plusHours(2));
        request.setExtraPrepMinutes(15);
    }

    @Test
    void setBusyMode_enablesWithRequestValues() {
        when(restaurantRepository.findById(1L)).thenReturn(Optional.of(restaurant));

        service.setBusyMode(1L, request);

        assertTrue(restaurant.getBusyMode());
        assertTrue(restaurant.getExtraPrepMinutes() == 15);
        verify(restaurantRepository).save(restaurant);
    }

    @Test
    void setBusyMode_keepsPrepMinutes_whenNull() {
        request.setExtraPrepMinutes(null);
        when(restaurantRepository.findById(1L)).thenReturn(Optional.of(restaurant));

        service.setBusyMode(1L, request);

        assertTrue(restaurant.getExtraPrepMinutes() == 0);
    }

    @Test
    void setBusyMode_throws_whenRestaurantNotFound() {
        when(restaurantRepository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.setBusyMode(99L, request));
    }

    @Test
    void assertAcceptingOrders_passes_whenNotBusy() {
        when(restaurantRepository.findById(1L)).thenReturn(Optional.of(restaurant));

        service.assertAcceptingOrders(1L);
        verify(restaurantRepository, never()).save(restaurant);
    }

    @Test
    void assertAcceptingOrders_clearsExpiredBusyMode() {
        restaurant.setBusyMode(true);
        restaurant.setBusyUntil(LocalDateTime.now().minusMinutes(5));
        when(restaurantRepository.findById(1L)).thenReturn(Optional.of(restaurant));

        service.assertAcceptingOrders(1L);

        assertFalse(restaurant.getBusyMode());
        verify(restaurantRepository).save(restaurant);
    }

    @Test
    void assertAcceptingOrders_throws_whenBusyAndNotExpired() {
        restaurant.setBusyMode(true);
        restaurant.setBusyUntil(LocalDateTime.now().plusHours(1));
        when(restaurantRepository.findById(1L)).thenReturn(Optional.of(restaurant));

        assertThrows(BusinessException.class, () -> service.assertAcceptingOrders(1L));
    }

    @Test
    void clearBusyMode_resetsFields() {
        restaurant.setBusyMode(true);
        restaurant.setBusyUntil(LocalDateTime.now());
        restaurant.setExtraPrepMinutes(10);
        when(restaurantRepository.findById(1L)).thenReturn(Optional.of(restaurant));

        service.clearBusyMode(1L);

        assertFalse(restaurant.getBusyMode());
        assertTrue(restaurant.getExtraPrepMinutes() == 0);
    }
}