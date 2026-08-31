package com.bhukkad.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response for step 1 of phone-first registration.
 *
 * <p>No JWT tokens are issued at this stage — the OTP must be verified first.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PhoneRegisterResponse {
    private String phoneNumber;
    private String message;
    private int otpExpiryMinutes;
}
