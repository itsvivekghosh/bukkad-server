package com.bhukkad.identity.api;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class OtpResendRequest {
    @NotBlank(message = "Phone number is required")
    private String phoneNumber;

    private String channel = "sms";
}
