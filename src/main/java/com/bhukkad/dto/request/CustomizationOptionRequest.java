package com.bhukkad.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class CustomizationOptionRequest {
    @NotBlank(message = "Customization name is required")
    private String name;

    private Boolean required = false;

    private Boolean multipleSelection = false;

    private Integer minSelection = 0;

    private Integer maxSelection;

    @NotEmpty(message = "At least one choice is required")
    private List<CustomizationChoiceRequest> choices;
}