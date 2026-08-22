package com.bhukkad.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FraudReviewActionResponse {
    private Long id;
    private Long fraudEventId;
    private String eventType;
    private Long customerId;
    private String action;
    private String status;
    private String notes;
    private Long reviewedBy;
    private String reviewedAt;
    private String createdAt;
}
