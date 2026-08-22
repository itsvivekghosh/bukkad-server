package com.bhukkad.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Authentication response containing JWT tokens and user details.
 *
 * <p>When a privileged account (ADMIN / RESTAURANT_OWNER) has TOTP MFA enabled,
 * {@link #mfaRequired} is {@code true}, {@link #mfaToken} carries a short-lived
 * token authorising the MFA-verify step, and the access/refresh tokens are
 * omitted until the second factor is confirmed.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {
    private String token;
    private String refreshToken;
    @Builder.Default
    private String tokenType = "Bearer";
    private Long userId;
    private String email;
    private String fullName;
    private String role;

    /** True when the login must be completed with a TOTP code. */
    @Builder.Default
    private boolean mfaRequired = false;

    /** Short-lived token authorising {@code POST /auth/mfa/verify}. */
    private String mfaToken;
}
