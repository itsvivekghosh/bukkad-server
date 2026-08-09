package com.bhukkad.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AddressResponse {
    private Long id;
    private String addressLine1;
    private String addressLine2;
    private String city;
    private String state;
    private String pincode;
    private String landmark;
    private String type;
    private String label;
    private Double latitude;
    private Double longitude;
    private Boolean isDefault;
}