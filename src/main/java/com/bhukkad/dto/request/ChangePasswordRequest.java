package com.bhukkad.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request body for the change-password endpoint.
 *
 * <p>Both the current and new passwords are submitted in the request body so
 * neither value ever appears in a URL or query string.
 */
@Data
public class ChangePasswordRequest {

    @NotBlank(message = "Current password is required")
    private String oldPassword;

    @NotBlank(message = "New password is required")
    private String newPassword;
}
