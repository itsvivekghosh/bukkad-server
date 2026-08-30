package com.bhukkad.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * Unified phone sign-in: send a one-time password to the given phone number
 * via SMS or WhatsApp. The OTP is sent regardless of whether an account exists
 * — verification later creates the account on first sign-in or logs in an
 * existing user (create-or-login).
 */
@Data
public class PhoneSendOtpRequest {

    @NotBlank(message = "Phone number is required")
    @Pattern(regexp = "^[0-9]{10}$", message = "Phone number must be exactly 10 digits")
    private String phoneNumber;

    /** OTP delivery channel: "sms" or "whatsapp". Defaults to "sms" when blank. */
    private String channel = "sms";
}
