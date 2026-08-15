package com.bhukkad.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RiderLocationResponse {
    private Long orderId;
    private Long agentId;
    private Double latitude;
    private Double longitude;
    private String recordedAt;
}
