package com.bhukkad.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RestaurantSettlementResponse {
    private Long id;
    private Long restaurantId;
    private Long orderId;
    private String orderNumber;
    private Double orderAmount;
    private Double commissionAmount;
    private Double netAmount;
    private String status;
    private String settledAt;
    private String createdAt;
}
