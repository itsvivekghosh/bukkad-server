package com.bhukkad.dto.response;

import lombok.Builder;
import lombok.Data;

/** Delivery zone with pricing configuration. */
@Data
@Builder
public class DeliveryZoneResponse {
    private Long id;
    private String name;
    private String city;
    private Double centerLatitude;
    private Double centerLongitude;
    private Double radiusKm;
    private Double baseDeliveryFee;
    private Double perKmFee;
    private Double surgeMultiplier;
    private Double freeDeliveryAbove;
    private Boolean isActive;
}
