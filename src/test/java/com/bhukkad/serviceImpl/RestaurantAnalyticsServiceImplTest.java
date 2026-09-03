package com.bhukkad.serviceImpl;

import com.bhukkad.dto.response.RestaurantAnalyticsResponse;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.entity.RestaurantOwner;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.common.error.UnauthorizedException;
import com.bhukkad.repository.OrderItemRepository;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.repository.RestaurantRepository;
import com.bhukkad.security.SecurityUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RestaurantAnalyticsServiceImplTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderItemRepository orderItemRepository;

    @Mock
    private RestaurantRepository restaurantRepository;

    @Mock
    private SecurityUtils securityUtils;

    @InjectMocks
    private RestaurantAnalyticsServiceImpl service;

    private Restaurant ownedRestaurant(Long id) {
        RestaurantOwner owner = new RestaurantOwner();
        owner.setId(1L);
        Restaurant restaurant = new Restaurant();
        restaurant.setId(id);
        restaurant.setName("Spice Kitchen");
        restaurant.setOwner(owner);
        return restaurant;
    }

    @Test
    void getAnalytics_restaurantNotFound_throws() {
        when(restaurantRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getAnalytics(1L, 7));
    }

    @Test
    void getAnalytics_notOwner_throws() {
        Restaurant restaurant = ownedRestaurant(1L);
        restaurant.getOwner().setId(42L);
        when(restaurantRepository.findById(1L)).thenReturn(Optional.of(restaurant));
        when(securityUtils.getCurrentUserId()).thenReturn(1L);

        assertThrows(UnauthorizedException.class, () -> service.getAnalytics(1L, 7));
    }

    @Test
    void getAnalytics_success_fullFlow() {
        Restaurant restaurant = ownedRestaurant(1L);
        LocalDate today = LocalDate.now();
        when(restaurantRepository.findById(1L)).thenReturn(Optional.of(restaurant));
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(orderRepository.countByRestaurantIdAndCreatedAtAfter(eq(1L), any(LocalDateTime.class)))
                .thenReturn(10L);
        when(orderRepository.countByRestaurantIdAndStatusAndCreatedAtAfter(
                eq(1L), eq(Order.OrderStatus.DELIVERED), any(LocalDateTime.class))).thenReturn(4L);
        when(orderRepository.countByRestaurantIdAndStatusAndCreatedAtAfter(
                eq(1L), eq(Order.OrderStatus.CANCELLED), any(LocalDateTime.class))).thenReturn(2L);
        when(orderRepository.sumRestaurantRevenueSince(eq(1L), any(LocalDateTime.class))).thenReturn(1000.0);
        when(orderRepository.countRestaurantOrdersGroupedByStatus(eq(1L), any(LocalDateTime.class)))
                .thenReturn(List.of(
                        new Object[]{Order.OrderStatus.DELIVERED, 7L},
                        new Object[]{Order.OrderStatus.DELIVERED, 2L},
                        new Object[]{Order.OrderStatus.CANCELLED, 1L}));
        when(orderItemRepository.findTopSellingItems(eq(1L), any(LocalDateTime.class)))
                .thenReturn(List.of(
                        new Object[]{99L, "Biryani", 5L, 300.0},
                        new Object[]{98L, "Paneer", 3L, 150.25}));
        // Single aggregate query replaces the per-day loop — return one row for
        // the oldest day so the response has a non-zero daily entry.
        when(orderRepository.findDailyDeliveredAggregates(eq(1L), any(LocalDateTime.class)))
                .thenReturn(List.<Object[]>of(
                        new Object[]{java.sql.Date.valueOf(today.minusDays(6)), 4L, 1000.0}));
        when(orderRepository.findHourlyDeliveredCounts(eq(1L), any(LocalDateTime.class)))
                .thenReturn(List.<Object[]>of(new Object[]{12, 2L}));

        RestaurantAnalyticsResponse response = service.getAnalytics(1L, 7);

        assertEquals(1L, response.getRestaurantId());
        assertEquals("Spice Kitchen", response.getRestaurantName());
        assertEquals(7, response.getPeriodDays());
        assertEquals(1000.0, response.getTotalRevenue());
        assertEquals(10L, response.getTotalOrders());
        assertEquals(4L, response.getDeliveredOrders());
        assertEquals(2L, response.getCancelledOrders());
        assertEquals(250.0, response.getAverageOrderValue());
        assertEquals(9L, response.getOrdersByStatus().get("DELIVERED"));
        assertEquals(1L, response.getOrdersByStatus().get("CANCELLED"));
        assertEquals(2, response.getOrdersByStatus().size());
        assertEquals(2, response.getTopMenuItems().size());
        assertEquals(99L, response.getTopMenuItems().get(0).getMenuItemId());
        assertEquals("Biryani", response.getTopMenuItems().get(0).getName());
        assertEquals(5L, response.getTopMenuItems().get(0).getQuantitySold());
        assertEquals(300.0, response.getTopMenuItems().get(0).getRevenue());
        assertEquals(7, response.getDailyRevenue().size());
        assertEquals(today.minusDays(6).toString(),
                response.getDailyRevenue().get(0).getDate());
        assertEquals(1000.0, response.getDailyRevenue().get(0).getRevenue());
        assertEquals(4L, response.getDailyRevenue().get(0).getOrderCount());
        // Peak-hour analytics
        assertEquals(24, response.getHourlyVolume().size());
        assertEquals(2L, response.getHourlyVolume().get(12).getOrderCount());
        assertEquals(12, response.getPeakHourOfDay());
        assertEquals(2L, response.getPeakHourOrderCount());
    }

    @Test
    void getAnalytics_nullRevenueAndNoDeliveries_producesZeroValues() {
        Restaurant restaurant = ownedRestaurant(1L);
        when(restaurantRepository.findById(1L)).thenReturn(Optional.of(restaurant));
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(orderRepository.countByRestaurantIdAndCreatedAtAfter(eq(1L), any(LocalDateTime.class)))
                .thenReturn(0L);
        when(orderRepository.countByRestaurantIdAndStatusAndCreatedAtAfter(
                eq(1L), eq(Order.OrderStatus.DELIVERED), any(LocalDateTime.class))).thenReturn(0L);
        when(orderRepository.countByRestaurantIdAndStatusAndCreatedAtAfter(
                eq(1L), eq(Order.OrderStatus.CANCELLED), any(LocalDateTime.class))).thenReturn(0L);
        when(orderRepository.sumRestaurantRevenueSince(eq(1L), any(LocalDateTime.class))).thenReturn(null);
        when(orderRepository.countRestaurantOrdersGroupedByStatus(eq(1L), any(LocalDateTime.class)))
                .thenReturn(List.of());
        when(orderItemRepository.findTopSellingItems(eq(1L), any(LocalDateTime.class)))
                .thenReturn(List.of());

        RestaurantAnalyticsResponse response = service.getAnalytics(1L, 1);

        assertEquals(0.0, response.getTotalRevenue());
        assertEquals(0.0, response.getAverageOrderValue());
        assertEquals(0, response.getOrdersByStatus().size());
        assertEquals(0, response.getTopMenuItems().size());
    }

    @Test
    void getAnalytics_daysClampedToMinimumOfOne() {
        Restaurant restaurant = ownedRestaurant(1L);
        when(restaurantRepository.findById(1L)).thenReturn(Optional.of(restaurant));
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(orderRepository.countByRestaurantIdAndCreatedAtAfter(eq(1L), any(LocalDateTime.class)))
                .thenReturn(0L);
        when(orderRepository.countByRestaurantIdAndStatusAndCreatedAtAfter(
                eq(1L), eq(Order.OrderStatus.DELIVERED), any(LocalDateTime.class))).thenReturn(0L);
        when(orderRepository.countByRestaurantIdAndStatusAndCreatedAtAfter(
                eq(1L), eq(Order.OrderStatus.CANCELLED), any(LocalDateTime.class))).thenReturn(0L);
        when(orderRepository.sumRestaurantRevenueSince(eq(1L), any(LocalDateTime.class))).thenReturn(0.0);
        when(orderRepository.countRestaurantOrdersGroupedByStatus(eq(1L), any(LocalDateTime.class)))
                .thenReturn(List.of());
        when(orderItemRepository.findTopSellingItems(eq(1L), any(LocalDateTime.class)))
                .thenReturn(List.of());

        assertEquals(1, service.getAnalytics(1L, 0).getPeriodDays());
        assertEquals(1, service.getAnalytics(1L, -5).getPeriodDays());
    }

    @Test
    void getAnalytics_daysClampedToMaximumOfNinety() {
        Restaurant restaurant = ownedRestaurant(1L);
        when(restaurantRepository.findById(1L)).thenReturn(Optional.of(restaurant));
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(orderRepository.countByRestaurantIdAndCreatedAtAfter(eq(1L), any(LocalDateTime.class)))
                .thenReturn(0L);
        when(orderRepository.countByRestaurantIdAndStatusAndCreatedAtAfter(
                eq(1L), eq(Order.OrderStatus.DELIVERED), any(LocalDateTime.class))).thenReturn(0L);
        when(orderRepository.countByRestaurantIdAndStatusAndCreatedAtAfter(
                eq(1L), eq(Order.OrderStatus.CANCELLED), any(LocalDateTime.class))).thenReturn(0L);
        when(orderRepository.sumRestaurantRevenueSince(eq(1L), any(LocalDateTime.class))).thenReturn(0.0);
        when(orderRepository.countRestaurantOrdersGroupedByStatus(eq(1L), any(LocalDateTime.class)))
                .thenReturn(List.of());
        when(orderItemRepository.findTopSellingItems(eq(1L), any(LocalDateTime.class)))
                .thenReturn(List.of());

        RestaurantAnalyticsResponse response = service.getAnalytics(1L, 91);

        assertEquals(90, response.getPeriodDays());
        assertEquals(90, response.getDailyRevenue().size());
        assertEquals(LocalDate.now().minusDays(89).toString(),
                response.getDailyRevenue().get(0).getDate());
    }

    @Test
    void getAnalytics_topSellingItemsLimitedToTen() {
        Restaurant restaurant = ownedRestaurant(1L);
        when(restaurantRepository.findById(1L)).thenReturn(Optional.of(restaurant));
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(orderRepository.countByRestaurantIdAndCreatedAtAfter(eq(1L), any(LocalDateTime.class)))
                .thenReturn(0L);
        when(orderRepository.countByRestaurantIdAndStatusAndCreatedAtAfter(
                eq(1L), eq(Order.OrderStatus.DELIVERED), any(LocalDateTime.class))).thenReturn(0L);
        when(orderRepository.countByRestaurantIdAndStatusAndCreatedAtAfter(
                eq(1L), eq(Order.OrderStatus.CANCELLED), any(LocalDateTime.class))).thenReturn(0L);
        when(orderRepository.sumRestaurantRevenueSince(eq(1L), any(LocalDateTime.class))).thenReturn(0.0);
        when(orderRepository.countRestaurantOrdersGroupedByStatus(eq(1L), any(LocalDateTime.class)))
                .thenReturn(List.of());

        List<Object[]> rows = new java.util.ArrayList<>();
        for (long i = 1; i <= 12; i++) {
            rows.add(new Object[]{i, "Item " + i, i, 100.0});
        }
        when(orderItemRepository.findTopSellingItems(eq(1L), any(LocalDateTime.class)))
                .thenReturn(rows);

        RestaurantAnalyticsResponse response = service.getAnalytics(1L, 1);

        assertEquals(10, response.getTopMenuItems().size());
        assertEquals(1L, response.getTopMenuItems().get(0).getMenuItemId());
        assertEquals(10L, response.getTopMenuItems().get(9).getMenuItemId());
    }
}
