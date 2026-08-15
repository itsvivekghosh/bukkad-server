package com.bhukkad.restaurant;

import com.bhukkad.dto.response.RestaurantAnalyticsResponse;
import com.bhukkad.dto.response.RestaurantDashboardResponse;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.RestaurantRepository;
import com.bhukkad.service.RestaurantAnalyticsService;
import com.bhukkad.settlement.RestaurantSettlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Restaurant owner dashboard 2.0 combining analytics, settlements, and ops status (V16).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RestaurantDashboardService {

    private final RestaurantAnalyticsService restaurantAnalyticsService;
    private final RestaurantSettlementService restaurantSettlementService;
    private final RestaurantRepository restaurantRepository;

    public RestaurantDashboardResponse getDashboard(Long restaurantId, int days) {
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found"));
        RestaurantAnalyticsResponse analytics = restaurantAnalyticsService.getAnalytics(restaurantId, days);
        double pendingAmount = restaurantSettlementService.getPendingSettlementAmount(restaurantId);

        return RestaurantDashboardResponse.builder()
                .restaurantId(analytics.getRestaurantId())
                .restaurantName(analytics.getRestaurantName())
                .periodDays(analytics.getPeriodDays())
                .totalRevenue(analytics.getTotalRevenue())
                .totalOrders(analytics.getTotalOrders())
                .deliveredOrders(analytics.getDeliveredOrders())
                .cancelledOrders(analytics.getCancelledOrders())
                .averageOrderValue(analytics.getAverageOrderValue())
                .pendingSettlementAmount(pendingAmount)
                .pendingSettlementCount(pendingAmount > 0 ? 1L : 0L)
                .busyMode(restaurant.getBusyMode())
                .extraPrepMinutes(restaurant.getExtraPrepMinutes())
                .ordersByStatus(analytics.getOrdersByStatus())
                .topMenuItems(analytics.getTopMenuItems())
                .dailyRevenue(analytics.getDailyRevenue())
                .build();
    }
}
