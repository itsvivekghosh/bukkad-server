package com.bhukkad.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Step 3 of the phone-first registration flow (profile completion).
 *
 * <p>Used by a phone-first customer to append email, full name, and password
 * to their existing account after OTP verification. This is required before
 * the account can be used with email-based login.
 */
@Data
public class CompleteProfileRequest {

    @NotBlank(message = "Email is required")
    @Email(message = "Email should be valid")
    private String email;

    @NotBlank(message = "Full name is required")
    @Size(min = 2, message = "Full name must be at least 2 characters")
    private String fullName;

    /**
     * Password to set for the account. Required if not set during registration.
     * Minimum 8 characters for security.
     */
    @Size(min = 8, message = "Password must be at least 8 characters")
    private String password;
}
