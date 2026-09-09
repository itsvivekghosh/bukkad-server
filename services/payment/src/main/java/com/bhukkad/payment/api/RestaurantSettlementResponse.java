package com.bhukkad.payment.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RestaurantSettlementResponse {

    private Long id;
    private Long settlementRunId;
    private Long restaurantId;
    private Long orderId;
    private Integer orderCount;
    private BigDecimal grossAmount;
    private BigDecimal commission;
    private BigDecimal netAmount;
    private String status;
    private String createdAt;
}
