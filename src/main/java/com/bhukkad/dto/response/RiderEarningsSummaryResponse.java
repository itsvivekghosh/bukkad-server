package com.bhukkad.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiderEarningsSummaryResponse {
    private Double pendingAmount;
    private Double paidAmount;
    private Double perDeliveryFee;
    private Long totalDeliveries;
}
