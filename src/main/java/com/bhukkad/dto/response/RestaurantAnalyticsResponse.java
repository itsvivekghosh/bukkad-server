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
    /** Order counts bucketed by hour of day (0–23) for the analytics window. */
    private List<HourlyVolumeStat> hourlyVolume;
    /** Index of the hour (0–23) with the highest delivered-order count, or -1 if empty. */
    private int peakHourOfDay;
    /** Delivered-order count at {@link #peakHourOfDay}. */
    private long peakHourOrderCount;

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

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HourlyVolumeStat {
        /** Hour of day, 0–23. */
        private int hour;
        private Long orderCount;
    }
}
