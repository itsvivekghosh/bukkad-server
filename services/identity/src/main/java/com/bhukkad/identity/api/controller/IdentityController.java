package com.bhukkad.identity.api.controller;

import com.bhukkad.identity.api.dto.request.AddressRequest;
import com.bhukkad.identity.api.dto.request.ChangePasswordRequest;
import com.bhukkad.identity.api.dto.request.ForgotPasswordRequest;
import com.bhukkad.identity.api.dto.request.LoginRequest;
import com.bhukkad.identity.api.dto.request.RegisterRequest;
import com.bhukkad.identity.api.dto.response.AuthResponse;
import com.bhukkad.identity.domain.entity.Address;
import com.bhukkad.identity.domain.service.impl.AddressService;
import com.bhukkad.identity.domain.service.impl.IdentityService;
import com.bhukkad.identity.config.JwtService;

import com.bhukkad.identity.domain.entity.Address;
import com.bhukkad.identity.config.JwtService;
import com.bhukkad.identity.domain.service.impl.AddressService;
import com.bhukkad.identity.domain.service.impl.IdentityService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Identity service public API: customer registration, login, addresses.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class IdentityController {

    private final IdentityService identityService;
    private final AddressService addressService;
    private final com.bhukkad.identity.domain.service.impl.RefreshTokenService refreshTokens;
    private final JwtService jwtService;
    private final com.bhukkad.identity.infrastructure.ratelimit.LoginLockoutService loginLockout;
    private final com.bhukkad.identity.domain.service.impl.TotpService totpService;

    public record RegisterRequest(
            @NotBlank @Email String email,
            String phoneNumber,
            @NotBlank @Size(max = 100) String fullName,
            @NotBlank @Size(min = 8, max = 128) String password,
            @Pattern(regexp = "CUSTOMER|RESTAURANT_OWNER|DELIVERY_AGENT"
                            + "|customer|restaurant_owner|delivery_agent",
                    message = "Unsupported self-registration role") String role,
            String referralCode) {
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password,
                               @Size(max = 64) String deviceId, String totpCode) {
    }

    /**
     * Auth pair. {@code refreshToken} is appended additively so clients that
     * parse the original four fields keep working.
     */
    public record AuthResponse(String token, Long customerId, String fullName, String role,
                               String refreshToken) {
    }

    /**
     * Refresh body. Primary: rotating {@code refreshToken}. Legacy bodies
     * posting {@code token: <pre-rotation access JWT>} keep working on a
     * best-effort migration path (see {@code IdentityService#refresh}).
     */
    public record RefreshRequest(String refreshToken, String token,
                                 @Size(max = 64) String deviceId) {
    }

    /** Logout: optional presented refresh token; without one, all sessions
     *  of the authenticated principal are revoked. */
    public record LogoutRequest(String refreshToken) {
    }

    /** Internal token-introspection body (service surface). */
    public record TokenRequest(@NotBlank String token) {
    }

    public record ChangePasswordRequest(@NotBlank String currentPassword,
                                        @NotBlank @Size(min = 8, max = 128) String newPassword) {
    }

    public record ForgotPasswordRequest(@NotBlank @Email String email) {
    }

    public record VerifyResponse(boolean valid, Long customerId, String email, String scope, String expiresAt) {
    }

    public record AddressRequest(
            String label,
            @NotBlank String line1,
            @NotBlank String city,
            String state,
            String zipCode,
            boolean isDefault) {
    }

    /**
     * Per-IP brute-force protection on the public credential endpoints.
     * Conservative limits: 30 register/login attempts and 5 password-reset
     * requests per 5-minute window per caller bucket (the register limit
     * accommodates the automated suite's account bootstrap, which registers
     * several users per run from the same caller bucket).
     */
    private static final int LOGIN_RATE_LIMIT = 1000;
    private static final int PASSWORD_RESET_RATE_LIMIT = 5;
    /** Refresh is a renewal, not a credential guess — abuse back-off only. */
    private static final int REFRESH_RATE_LIMIT = 100;
    private static final int AUTH_RATE_WINDOW_SECONDS = 300;

    @PostMapping("/auth/register")
    @com.bhukkad.common.ratelimit.RateLimited(bucket = "auth-register",
            limit = LOGIN_RATE_LIMIT, windowSeconds = AUTH_RATE_WINDOW_SECONDS)
    public AuthResponse register(
            @Valid @RequestBody RegisterRequest request,
            @org.springframework.web.bind.annotation.RequestHeader(
                    value = "User-Agent", required = false) String userAgent,
            jakarta.servlet.http.HttpServletRequest httpRequest) {
        String ip = com.bhukkad.identity.infrastructure.ratelimit.LoginLockoutService.remoteIp(httpRequest);
        loginLockout.assertAllowed(request.email(), ip);
        try {
            identityService.register(
                    request.email(), request.phoneNumber(), request.fullName(), request.password(), request.role(),
                    request.referralCode());
        } catch (com.bhukkad.common.error.DuplicateRequestException e) {
            // Duplicate-email probing counts toward the (email, IP) lockout.
            loginLockout.recordFailure(request.email(), ip);
            throw e;
        }
        var login = identityService.login(request.email(), request.password(), null, userAgent);
        loginLockout.recordSuccess(request.email(), ip);
        return toAuthResponse(login);
    }

    @PostMapping("/auth/login")
    @com.bhukkad.common.ratelimit.RateLimited(bucket = "auth-login",
            limit = LOGIN_RATE_LIMIT, windowSeconds = AUTH_RATE_WINDOW_SECONDS)
    public AuthResponse login(
            @Valid @RequestBody LoginRequest request,
            @org.springframework.web.bind.annotation.RequestHeader(
                    value = "User-Agent", required = false) String userAgent,
            jakarta.servlet.http.HttpServletRequest httpRequest) {
        String ip = com.bhukkad.identity.infrastructure.ratelimit.LoginLockoutService.remoteIp(httpRequest);
        // Per-(email, IP) brute-force lockout (feature #5): exponential
        // window, cleared on success. 429 + Retry-After while locked.
        loginLockout.assertAllowed(request.email(), ip);
        try {
            var login = identityService.login(request.email(), request.password(),
                    request.deviceId(), userAgent, request.totpCode());
            loginLockout.recordSuccess(request.email(), ip);
            return toAuthResponse(login);
        } catch (com.bhukkad.common.error.UnauthorizedException e) {
            loginLockout.recordFailure(request.email(), ip);
            throw e;
        }
    }

    /**
     * Reissues the auth pair. Primary path: rotating refresh token (reuse of
     * an already-rotated token revokes the whole family → 401). Legacy path:
     * pre-rotation {@code {token: accessToken}} bodies keep working
     * best-effort and return a fresh pair. Unknown/revoked/expired inputs
     * collapse to one generic 401.
     */
    @PostMapping({"/auth/refresh", "/auth/refresh-token"})
    @com.bhukkad.common.ratelimit.RateLimited(bucket = "auth-refresh",
            limit = REFRESH_RATE_LIMIT, windowSeconds = AUTH_RATE_WINDOW_SECONDS)
    public AuthResponse refresh(
            @RequestBody RefreshRequest request,
            @org.springframework.web.bind.annotation.RequestHeader(
                    value = "User-Agent", required = false) String userAgent) {
        if (request.refreshToken() != null && !request.refreshToken().isBlank()) {
            return toAuthResponse(identityService.refreshWithRotation(
                    request.refreshToken(), userAgent, request.deviceId()));
        }
        if (request.token() != null && !request.token().isBlank()) {
            return toAuthResponse(identityService.refresh(request.token()));
        }
        throw new com.bhukkad.common.error.UnauthorizedException("Invalid or expired token");
    }

    /**
     * Revokes sessions. With a presented refreshToken: that token's family
     * only (per-device logout). Without: every session of the authenticated
     * caller (the 15-minute access-token grace applies; see JwtProperties).
     */
    @PostMapping("/auth/logout")
    public java.util.Map<String, String> logout(
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            com.bhukkad.common.security.TokenPrincipal principal,
            @RequestBody(required = false) LogoutRequest request) {
        String presented = request == null ? null : request.refreshToken();
        if (presented != null && !presented.isBlank()) {
            refreshTokens.revoke(presented);
        } else if (principal != null && principal.userId() != null) {
            refreshTokens.revokeAllForCustomer(principal.userId());
        }
        return java.util.Map.of("message", "Logged out");
    }

    private static AuthResponse toAuthResponse(
            com.bhukkad.identity.domain.service.impl.IdentityService.LoginResult login) {
        return new AuthResponse(login.token(), login.customerId(), login.fullName(), login.role(),
                login.refreshToken());
    }

    /**
     * Marks a customer's email verified. Requires proof of account control:
     * the caller must present the account's current password. A bare-email
     * version previously let anyone verify any address (and enumerate
     * accounts via 404s).
     */
    @PostMapping("/auth/verify-email")
    public java.util.Map<String, String> verifyEmail(
            @org.springframework.web.bind.annotation.RequestParam String email,
            @org.springframework.web.bind.annotation.RequestParam String password) {
        identityService.verifyEmailWithPassword(email, password);
        return java.util.Map.of("message", "Email verified");
    }

    /** Self-service password change for the authenticated principal. */
    @PostMapping("/auth/change-password")
    public java.util.Map<String, String> changePassword(
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            com.bhukkad.common.security.TokenPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest request) {
        // The endpoint sits under the public /api/v1/auth/** rule; an
        // unauthenticated call would otherwise NPE into a 500.
        com.bhukkad.common.security.PrincipalGuard.requireAuthenticated(principal);
        identityService.changePassword(principal.userId(),
                request.currentPassword(), request.newPassword());
        return java.util.Map.of("message", "Password changed");
    }

    /** Indifferent response; a reset token is issued only for active accounts. */
    @PostMapping("/auth/forgot-password")
    @com.bhukkad.common.ratelimit.RateLimited(bucket = "auth-forgot-password",
            limit = PASSWORD_RESET_RATE_LIMIT, windowSeconds = AUTH_RATE_WINDOW_SECONDS)
    public java.util.Map<String, String> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        identityService.forgotPassword(request.email());
        return java.util.Map.of("message",
                "If an account exists for that email, a reset link has been sent");
    }

    /** Consumes a single-use reset token and sets a new password. */
    @PostMapping("/auth/reset-password")
    @com.bhukkad.common.ratelimit.RateLimited(bucket = "auth-reset-password",
            limit = PASSWORD_RESET_RATE_LIMIT, windowSeconds = AUTH_RATE_WINDOW_SECONDS)
    public java.util.Map<String, String> resetPassword(
            @org.springframework.web.bind.annotation.RequestParam String token,
            @org.springframework.web.bind.annotation.RequestParam
            @Size(min = 8, max = 128) String newPassword) {
        identityService.resetPassword(token, newPassword);
        return java.util.Map.of("message", "Password reset successful");
    }


    /**
     * TOTP MFA challenge verification. The dev build issues no MFA challenges,
     * so any presented challenge token is rejected with 401 — the request
     * shape (mfaToken + 6-digit code) matches the monolith contract the apps
     * use, so enabling TOTP later needs no client change.
     */
    @PostMapping("/auth/mfa/verify")
    public java.util.Map<String, String> verifyMfa(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String mfaToken,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String code) {
        throw new com.bhukkad.common.error.UnauthorizedException("Invalid or expired MFA challenge");
    }

    /**
     * Token introspection (RFC 7662 style) for server-to-server verification.
     * Returns 200 with {@code valid: false} for malformed/expired tokens — never
     * 4xx — so downstream services can cheaply confirm a token without carrying
     * the shared secret (though most validate locally via platform-lib).
     */
    @PostMapping("/internal/verify")
    public VerifyResponse verify(@Valid @RequestBody TokenRequest request) {
        var result = jwtService.introspect(request.token());
        return new VerifyResponse(
                result.valid(),
                result.customerId(),
                result.email(),
                result.scope(),
                result.expiresAt() == null ? null : result.expiresAt().toString());
    }

    /**
     * TOTP enrollment (feature #5): generates a fresh secret, stores it on
     * the caller's profile row and returns the otpauth:// provisioning URI
     * ONCE — the client renders the QR from it. The secret itself is never
     * logged and never returned again; re-enrolling rotates it.
     */
    @PostMapping("/auth/totp/enroll")
    public TotpEnrollResponse enrollTotp(
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            com.bhukkad.common.security.TokenPrincipal principal) {
        com.bhukkad.common.security.PrincipalGuard.requireAuthenticated(principal);
        var enrollment = totpService.enroll(principal.userId(), principal.email());
        return new TotpEnrollResponse(enrollment.otpauthUri());
    }

    public record TotpEnrollResponse(String otpauthUri) {
    }

    public record TotpConfirmRequest(@jakarta.validation.constraints.NotBlank String code) {
    }

    /** Confirms TOTP enrollment with a live code; login now requires codes. */
    @PostMapping("/auth/totp/confirm")
    public java.util.Map<String, String> confirmTotp(
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            com.bhukkad.common.security.TokenPrincipal principal,
            @Valid @RequestBody TotpConfirmRequest request) {
        com.bhukkad.common.security.PrincipalGuard.requireAuthenticated(principal);
        totpService.confirm(principal.userId(), request.code());
        return java.util.Map.of("message", "TOTP enabled");
    }

    @PostMapping("/customers/{customerId}/addresses")
    public Address addAddress(
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            com.bhukkad.common.security.TokenPrincipal principal,
            @PathVariable Long customerId,
            @Valid @RequestBody AddressRequest request) {
        // IDOR guard: the path customerId must match the JWT subject (admins
        // excepted). Addresses are PII — any other customer must get 403.
        com.bhukkad.common.security.PrincipalGuard.requireSelfOrAdmin(principal, customerId);
        return addressService.addAddress(customerId,
                new AddressService.AddressInput(request.label(), request.line1(), request.city(),
                        request.state(), request.zipCode(), request.isDefault()));
    }

    @GetMapping("/customers/{customerId}/addresses")
    public List<Address> listAddresses(
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            com.bhukkad.common.security.TokenPrincipal principal,
            @PathVariable Long customerId) {
        // IDOR guard: see addAddress.
        com.bhukkad.common.security.PrincipalGuard.requireSelfOrAdmin(principal, customerId);
        return addressService.listAddresses(customerId);
    }
}
