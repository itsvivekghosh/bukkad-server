package com.bhukkad.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Admin request to create or update a per-city platform config. */
@Data
public class CityConfigRequest {

    @NotBlank(message = "City is required")
    private String city;

    @NotBlank(message = "Display name is required")
    private String displayName;

    private String currency;

    private String timezone;

    private String supportedPaymentMethods;

    private Double defaultMinOrderAmount;

    private Boolean isServiceable;

    private Boolean isActive;
}
