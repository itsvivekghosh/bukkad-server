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
public class DisputeResponse {

    private Long id;
    private Long paymentId;
    private Long customerId;
    private Long orderId;
    private String type;
    private String status;
    private String customerEvidence;
    private String riderEvidence;
    private String restaurantEvidence;
    private String resolutionNotes;
    private String resolution;
    private BigDecimal refundAmount;
    private Long resolvedBy;
    private String resolvedAt;
    private String createdAt;
}
