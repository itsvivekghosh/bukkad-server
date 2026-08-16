package com.bhukkad.dto.response;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommissionTierResponse {
    private String tierName;
    private double minOrders;
    private double maxOrders;
    private double commissionPercent;
    private String description;
}