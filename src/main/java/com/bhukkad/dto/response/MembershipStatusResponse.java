package com.bhukkad.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MembershipStatusResponse {
    private boolean active;
    private Long membershipId;
    private Long planId;
    private String planName;
    private String status;
    private Boolean freeDelivery;
    private Double discountPercent;
    private String startsAt;
    private String endsAt;
}
