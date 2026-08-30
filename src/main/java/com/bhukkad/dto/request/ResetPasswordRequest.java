package com.bhukkad.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request body for the reset-password endpoint.
 *
 * <p>The reset token and new password are submitted in the request body rather
 * than as query parameters so the password never appears in URLs, server logs,
 * or browser history.
 */
@Data
public class ResetPasswordRequest {

    @NotBlank(message = "Reset token is required")
    private String token;

    @NotBlank(message = "New password is required")
    private String newPassword;
}
