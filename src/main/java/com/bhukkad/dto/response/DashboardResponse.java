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
public class DashboardResponse {
    private long totalUsers;
    private long totalCustomers;
    private long totalRestaurantOwners;
    private long totalDeliveryAgents;
    private long totalRestaurants;
    private long activeRestaurants;
    private long totalOrders;
    private long pendingOrders;
    private long deliveredOrders;
    private long cancelledOrders;
    private double totalRevenue;
    private double todayRevenue;
    private long todayOrders;
    private List<Map<String, Object>> recentOrders;
    private List<Map<String, Object>> topRestaurants;
}