package com.bhukkad.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CustomerOrderStatsResponse {
    private long totalOrders;
    private long deliveredOrders;
    private long cancelledOrders;
    private double totalSpent;
    private int loyaltyPoints;
}
