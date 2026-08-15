package com.bhukkad.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/** Restaurant owner dashboard 2.0 with settlements, busy mode, and promotion impact (V16). */
@Data
@Builder
public class RestaurantDashboardResponse {
    private Long restaurantId;
    private String restaurantName;
    private int periodDays;
    private Double totalRevenue;
    private Long totalOrders;
    private Long deliveredOrders;
    private Long cancelledOrders;
    private Double averageOrderValue;
    private Double pendingSettlementAmount;
    private Long pendingSettlementCount;
    private Boolean busyMode;
    private Integer extraPrepMinutes;
    private Map<String, Long> ordersByStatus;
    private List<RestaurantAnalyticsResponse.TopMenuItemStat> topMenuItems;
    private List<RestaurantAnalyticsResponse.DailyRevenueStat> dailyRevenue;
    private Double estimatedLiveEtaMinutes;
}
