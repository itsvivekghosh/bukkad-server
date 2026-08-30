package com.bhukkad.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Login request. Supports both plaintext and encrypted password modes.
 *
 * <p>When the frontend sends an encrypted password (JWE), the caller populates
 * {@code encryptedPassword} instead of {@code password}. The {@code password}
 * field remains for backward compatibility with legacy clients and internal
 * service-to-service calls.
 */
@Data
public class LoginRequest {
    @NotBlank(message = "Email is required")
    @Email(message = "Email should be valid")
    private String email;

    /** Plaintext password — used when no encryption is applied. */
    private String password;

    /** JWE-encrypted password payload — preferred for web/mobile clients. */
    private String encryptedPassword;
}
