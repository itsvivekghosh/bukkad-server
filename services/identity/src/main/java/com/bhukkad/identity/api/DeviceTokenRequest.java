package com.bhukkad.identity.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DeviceTokenRequest {
    @NotBlank
    private String token;

    @NotNull
    private String platform;
}
