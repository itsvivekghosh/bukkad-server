package com.bhukkad.identity.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PhoneRegisterResponse {
    private String phoneNumber;
    private String message;
    private int otpExpiryMinutes;
}
