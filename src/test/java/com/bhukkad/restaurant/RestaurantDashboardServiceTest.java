package com.bhukkad.restaurant;

import com.bhukkad.dto.response.RestaurantAnalyticsResponse;
import com.bhukkad.dto.response.RestaurantDashboardResponse;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.RestaurantRepository;
import com.bhukkad.service.RestaurantAnalyticsService;
import com.bhukkad.settlement.RestaurantSettlementService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RestaurantDashboardServiceTest {

    @Mock
    private RestaurantAnalyticsService restaurantAnalyticsService;

    @Mock
    private RestaurantSettlementService restaurantSettlementService;

    @Mock
    private RestaurantRepository restaurantRepository;

    @InjectMocks
    private RestaurantDashboardService service;

    @Test
    void getDashboard_buildsResponseFromSources() {
        Restaurant restaurant = new Restaurant();
        restaurant.setId(7L);
        restaurant.setBusyMode(true);
        restaurant.setExtraPrepMinutes(15);
        when(restaurantRepository.findById(7L)).thenReturn(Optional.of(restaurant));

        RestaurantAnalyticsResponse analytics = RestaurantAnalyticsResponse.builder()
                .restaurantId(7L)
                .restaurantName("Spice Hub")
                .periodDays(30)
                .totalRevenue(50000.0)
                .totalOrders(100L)
                .deliveredOrders(90L)
                .cancelledOrders(5L)
                .averageOrderValue(500.0)
                .ordersByStatus(Map.of("DELIVERED", 90L))
                .topMenuItems(List.of())
                .dailyRevenue(List.of())
                .build();
        when(restaurantAnalyticsService.getAnalytics(7L, 30)).thenReturn(analytics);
        when(restaurantSettlementService.getPendingSettlementAmount(7L)).thenReturn(2500.0);

        RestaurantDashboardResponse response = service.getDashboard(7L, 30);

        assertEquals(7L, response.getRestaurantId());
        assertEquals("Spice Hub", response.getRestaurantName());
        assertEquals(30, response.getPeriodDays());
        assertEquals(50000.0, response.getTotalRevenue());
        assertEquals(100L, response.getTotalOrders());
        assertEquals(90L, response.getDeliveredOrders());
        assertEquals(5L, response.getCancelledOrders());
        assertEquals(500.0, response.getAverageOrderValue());
        assertEquals(2500.0, response.getPendingSettlementAmount());
        assertEquals(1L, response.getPendingSettlementCount());
        assertEquals(Boolean.TRUE, response.getBusyMode());
        assertEquals(15, response.getExtraPrepMinutes());
    }

    @Test
    void getDashboard_zeroPendingAmount_yieldsZeroCount() {
        Restaurant restaurant = new Restaurant();
        restaurant.setId(7L);
        when(restaurantRepository.findById(7L)).thenReturn(Optional.of(restaurant));

        RestaurantAnalyticsResponse analytics = RestaurantAnalyticsResponse.builder()
                .restaurantId(7L)
                .restaurantName("Spice Hub")
                .periodDays(30)
                .build();
        when(restaurantAnalyticsService.getAnalytics(7L, 30)).thenReturn(analytics);
        when(restaurantSettlementService.getPendingSettlementAmount(7L)).thenReturn(0.0);

        RestaurantDashboardResponse response = service.getDashboard(7L, 30);

        assertEquals(0L, response.getPendingSettlementCount());
        // Entity defaults: busyMode=false, extraPrepMinutes=0
        assertEquals(Boolean.FALSE, response.getBusyMode());
        assertEquals(0, response.getExtraPrepMinutes());
    }

    @Test
    void getDashboard_restaurantNotFound_throws() {
        when(restaurantRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getDashboard(99L, 7));
    }
}
