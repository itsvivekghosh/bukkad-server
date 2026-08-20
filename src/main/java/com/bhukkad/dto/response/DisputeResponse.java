package com.bhukkad.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DisputeResponse {
    private Long id;
    private Long orderId;
    private String orderNumber;
    private String type;
    private String status;
    private String customerEvidence;
    private String riderEvidence;
    private String restaurantEvidence;
    private String resolutionNotes;
    private String resolution;
    private Double refundAmount;
    private Long resolvedBy;
    private String resolvedAt;
    private String createdAt;
}
