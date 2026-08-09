package com.bhukkad.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CustomizationChoiceRequest {
    @NotBlank(message = "Choice name is required")
    private String name;

    private Double additionalPrice = 0.0;

    private Boolean available = true;
}