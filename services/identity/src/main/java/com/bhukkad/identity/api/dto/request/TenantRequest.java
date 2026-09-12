package com.bhukkad.identity.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class TenantRequest {
    @NotBlank(message = "Tenant name is required")
    private String name;

    @NotBlank(message = "Domain is required")
    private String domain;

    private String brandName;

    private String logoUrl;

    private String themeColor;

    @NotNull(message = "Currency is required")
    private String currency;

    private Boolean isActive;
}
