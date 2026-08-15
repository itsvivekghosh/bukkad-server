package com.bhukkad.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class RiderLocationRequest {
    @NotNull(message = "Latitude is required")
    private Double latitude;

    @NotNull(message = "Longitude is required")
    private Double longitude;
}
