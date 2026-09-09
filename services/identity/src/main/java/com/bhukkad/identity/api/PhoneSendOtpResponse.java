package com.bhukkad.identity.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PhoneSendOtpResponse {
    private String phoneNumber;
    private String message;
    private int otpExpiryMinutes;
    @Builder.Default
    @JsonProperty("isNewUser")
    private boolean isNewUser = false;
}
