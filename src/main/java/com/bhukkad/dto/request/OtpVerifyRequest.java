package com.bhukkad.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Step 2 of the phone-first registration flow.
 *
 * <p>The customer submits the 6-digit OTP they received via SMS or WhatsApp.
 * The backend validates the OTP against the Redis-stored hash and, on success,
 * issues the JWT access/refresh-token pair.
 */
@Data
public class OtpVerifyRequest {

    @NotBlank(message = "Phone number is required")
    private String phoneNumber;

    @NotBlank(message = "OTP code is required")
    private String code;
}
