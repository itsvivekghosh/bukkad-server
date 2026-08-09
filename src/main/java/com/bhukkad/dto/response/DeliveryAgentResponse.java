package com.bhukkad.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryAgentResponse {
    private Long id;
    private String fullName;
    private String phoneNumber;
    private String vehicleType;
    private String vehicleNumber;
    private Boolean available;
    private Double averageRating;
    private Integer totalDeliveries;
    private Double currentLatitude;
    private Double currentLongitude;
}