package com.bhukkad.dto.request;

import com.bhukkad.entity.User;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * Step 1 of the phone-first registration flow.
 *
 * <p>A customer signs up with only a phone number. The backend creates an
 * account with a placeholder email and sends a 6-digit OTP via SMS or WhatsApp.
 * The OTP must be verified before tokens are issued.
 */
@Data
public class PhoneRegisterRequest {

    @NotBlank(message = "Phone number is required")
    @Pattern(regexp = "^[0-9]{10}$", message = "Phone number must be exactly 10 digits")
    private String phoneNumber;

    private User.UserRole role = User.UserRole.CUSTOMER;

    /**
     * Optional: if a password is provided at registration time, the account
     * will be fully usable immediately after OTP verification. If omitted,
     * the customer completes their password in the profile-completion step.
     */
    private String password;

    /**
     * Optional: preferred OTP delivery channel. Values: "sms", "whatsapp".
     * Defaults to "sms" when not provided.
     */
    private String otpChannel = "sms";
}
