package com.bhukkad.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RestaurantAnalyticsResponse {
    private Long restaurantId;
    private String restaurantName;
    private int periodDays;
    private Double totalRevenue;
    private Long totalOrders;
    private Long deliveredOrders;
    private Long cancelledOrders;
    private Double averageOrderValue;
    private Map<String, Long> ordersByStatus;
    private List<TopMenuItemStat> topMenuItems;
    private List<DailyRevenueStat> dailyRevenue;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TopMenuItemStat {
        private Long menuItemId;
        private String name;
        private Long quantitySold;
        private Double revenue;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DailyRevenueStat {
        private String date;
        private Double revenue;
        private Long orderCount;
    }
}
