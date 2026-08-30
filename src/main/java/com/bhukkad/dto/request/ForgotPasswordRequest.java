package com.bhukkad.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request body for the forgot-password endpoint.
 *
 * <p>Moving the email from a query parameter to the request body prevents it
 * from appearing in server access logs, browser history, and proxy logs.
 */
@Data
public class ForgotPasswordRequest {

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be valid")
    private String email;
}
