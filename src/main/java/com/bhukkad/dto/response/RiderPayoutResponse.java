package com.bhukkad.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiderPayoutResponse {
    private Long id;
    private Long orderId;
    private String orderNumber;
    private Double amount;
    private String status;
    private String createdAt;
    private String paidAt;
}
