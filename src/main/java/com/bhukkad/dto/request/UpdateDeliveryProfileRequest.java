package com.bhukkad.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateDeliveryProfileRequest {

    @Size(max = 100)
    private String fullName;

    @Size(max = 15)
    private String phoneNumber;

    private String vehicleType;

    private String vehicleNumber;

    private String licenseNumber;
}
