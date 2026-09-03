package com.bhukkad.restaurant.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.restaurant.domain.Restaurant;
import com.bhukkad.restaurant.domain.RestaurantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RestaurantDashboardServiceTest {

    @Mock
    private RestaurantRepository restaurantRepository;

    @InjectMocks
    private RestaurantDashboardService service;

    private Restaurant restaurant;

    @BeforeEach
    void setUp() {
        restaurant = new Restaurant();
        restaurant.setId(1L);
        restaurant.setName("Spice Route");
        restaurant.setBusyMode(false);
        restaurant.setExtraPrepMinutes(0);
        restaurant.setBusyUntil(null);
    }

    @Test
    void getDashboard_returnsBasicView() {
        when(restaurantRepository.findById(1L)).thenReturn(Optional.of(restaurant));

        RestaurantDashboardService.RestaurantDashboardView view = service.getDashboard(1L);

        assertThat(view.restaurantId()).isEqualTo(1L);
        assertThat(view.name()).isEqualTo("Spice Route");
        assertThat(view.busyMode()).isFalse();
        assertThat(view.extraPrepMinutes()).isZero();
    }

    @Test
    void getDashboard_throws_whenMissing() {
        when(restaurantRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getDashboard(99L))
                .isInstanceOf(com.bhukkad.common.error.ResourceNotFoundException.class);
    }

    @Test
    void assertAcceptingOrders_passes_whenNotBusy() {
        when(restaurantRepository.findById(1L)).thenReturn(Optional.of(restaurant));
        service.assertAcceptingOrders(1L);
    }

    @Test
    void assertAcceptingOrders_throws_whenBusyAndActive() {
        restaurant.setBusyMode(true);
        restaurant.setBusyUntil(LocalDateTime.now().plusHours(1));
        when(restaurantRepository.findById(1L)).thenReturn(Optional.of(restaurant));

        assertThatThrownBy(() -> service.assertAcceptingOrders(1L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void assertAcceptingOrders_passes_whenBusyButExpired() {
        restaurant.setBusyMode(true);
        restaurant.setBusyUntil(LocalDateTime.now().minusMinutes(1));
        when(restaurantRepository.findById(1L)).thenReturn(Optional.of(restaurant));

        service.assertAcceptingOrders(1L);
    }
}