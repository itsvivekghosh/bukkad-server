package com.bhukkad.dto.request;

import lombok.Data;

/** Admin request to create or update a delivery zone. */
@Data
public class DeliveryZoneRequest {
    private String name;
    private String city;
    private Double centerLatitude;
    private Double centerLongitude;
    /** Alias accepted by API tests / older clients. */
    private Double latitude;
    /** Alias accepted by API tests / older clients. */
    private Double longitude;
    private Double radiusKm;
    private Double baseDeliveryFee;
    private Double perKmFee;
    private Double surgeMultiplier;
    private Double freeDeliveryAbove;
    private Boolean isActive;
}
