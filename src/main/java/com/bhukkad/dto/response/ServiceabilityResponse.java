package com.bhukkad.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ServiceabilityResponse {
    private boolean serviceable;
    private Long zoneId;
    private String zoneName;
    private Double estimatedDeliveryFee;
    private Double distanceKm;
    private Double surgeMultiplier;
}
