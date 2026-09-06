package com.bhukkad.identity.api;

import com.bhukkad.identity.domain.Address;
import com.bhukkad.identity.security.JwtService;
import com.bhukkad.identity.service.AddressService;
import com.bhukkad.identity.service.IdentityService;
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
    private final JwtService jwtService;

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

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
    }

    public record AuthResponse(String token, Long customerId, String fullName, String role) {
    }

    /** Refresh-token body: the apps post {@code {"refreshToken": "..."}}. */
    public record RefreshBody(@NotBlank String refreshToken) {
        public String token() {
            return refreshToken;
        }
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
     * Conservative limits: 10 login attempts and 5 password-reset requests
     * per 5-minute window per caller bucket.
     */
    private static final int LOGIN_RATE_LIMIT = 10;
    private static final int PASSWORD_RESET_RATE_LIMIT = 5;
    private static final int AUTH_RATE_WINDOW_SECONDS = 300;

       @PostMapping("/auth/register")
    @com.bhukkad.common.ratelimit.RateLimited(bucket = "auth-register",
            limit = LOGIN_RATE_LIMIT, windowSeconds = AUTH_RATE_WINDOW_SECONDS)
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        identityService.register(
                request.email(), request.phoneNumber(), request.fullName(), request.password(), request.role(),
                request.referralCode());
        var login = identityService.login(request.email(), request.password());
        return new AuthResponse(login.token(), login.customerId(), login.fullName(), login.role());
    }

    @PostMapping("/auth/login")
    @com.bhukkad.common.ratelimit.RateLimited(bucket = "auth-login",
            limit = LOGIN_RATE_LIMIT, windowSeconds = AUTH_RATE_WINDOW_SECONDS)
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        var login = identityService.login(request.email(), request.password());
        return new AuthResponse(login.token(), login.customerId(), login.fullName(), login.role());
    }

    /**
     * Rotates a still-valid access token into a fresh one (see
     * {@link com.bhukkad.identity.service.IdentityService#refresh(String)}).
     */
    @PostMapping({"/auth/refresh", "/auth/refresh-token"})
    public AuthResponse refresh(@Valid @RequestBody RefreshBody request) {
        var login = identityService.refresh(request.token());
        return new AuthResponse(login.token(), login.customerId(), login.fullName(), login.role());
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
     * Client-side logout acknowledgment. Access tokens are short-lived JWTs;
     * there is no server session to destroy, so the endpoint exists to give
     * the apps a canonical logout call (and would revoke refresh tokens once
     * a persistent refresh-token store lands).
     */
    @PostMapping("/auth/logout")
    public java.util.Map<String, String> logout() {
        return java.util.Map.of("message", "Logged out");
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