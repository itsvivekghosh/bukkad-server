package com.bhukkad.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Carries the refresh token for the {@code POST /auth/refresh-token} exchange.
 *
 * <p>The refresh token is a long-lived credential and is therefore submitted in
 * the request body (as returned by login/register in {@code data.refreshToken})
 * rather than in the {@code Authorization} header, which is reserved for the
 * short-lived access token.</p>
 */
@Data
public class RefreshTokenRequest {

    @NotBlank(message = "Refresh token is required")
    private String refreshToken;
}
