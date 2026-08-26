package com.bhukkad.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request to resend an OTP for the phone-first registration flow.
 */
@Data
public class OtpResendRequest {

    @NotBlank(message = "Phone number is required")
    private String phoneNumber;

    /** "sms" or "whatsapp". Defaults to "sms" on the server side if blank. */
    private String channel = "sms";
}
