package com.bhukkad.controller;

import com.bhukkad.config.ApiPaths;

import com.bhukkad.dto.request.LoginRequest;
import com.bhukkad.dto.request.RegisterRequest;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.AuthResponse;
import com.bhukkad.dto.response.BlankResponse;
import com.bhukkad.fraud.FraudDetectionService;
import com.bhukkad.fraud.FraudEventTypes;
import com.bhukkad.ratelimit.RateLimited;
import com.bhukkad.service.AuthService;
import com.bhukkad.util.RequestUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Public authentication surface: registration, login, token lifecycle and password flows.
 *
 * <p><strong>Two independent abuse defences guard the credential endpoints.</strong>
 * They are not redundant, because they key on different things:
 * <ul>
 *   <li>{@link RateLimited} throttles per authenticated principal or per submitted
 *       identifier, which caps how fast a single account can be hammered.</li>
 *   <li>{@link FraudDetectionService} counts per source IP and per device fingerprint,
 *       which is the only dimension that catches an attacker cycling through thousands
 *       of distinct emails — each one individually under its own rate limit.</li>
 * </ul>
 *
 * <p>Both checks run before any credential verification or persistence, so a blocked
 * caller never reaches the password hasher (the most expensive step in the request)
 * and never creates a partial account row.
 */
@RestController
@RequestMapping(ApiPaths.V1_PREFIX + "/auth")
@RequiredArgsConstructor
@Tag(name = "Auth", description = "REST endpoints for Auth")
public class AuthController {

    private final AuthService authService;
    private final FraudDetectionService fraudDetectionService;

    /**
     * Creates a customer account.
     *
     * <p>The fraud check passes a {@code null} customer id because no account exists
     * yet — attribution is purely by IP and device fingerprint, which is exactly the
     * signal that matters for bulk-signup abuse (promo farming, referral self-dealing).
     *
     * @throws com.bhukkad.exception.FraudBlockedException as 429 when the source has
     *         exceeded the {@code auth-register} threshold within the detection window
     */
    @PostMapping("/register")
    @RateLimited("auth-register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(@Valid @RequestBody RegisterRequest request) {
        fraudDetectionService.checkAndBlock(null, FraudEventTypes.AUTH_REGISTER);
        AuthResponse response = authService.register(request);
        return ResponseEntity.ok(ApiResponse.success("Registration successful", response));
    }

    /**
     * Authenticates a customer and issues a JWT pair.
     *
     * <p>The fraud check runs before {@code authService.login} so that credential
     * stuffing is stopped ahead of the BCrypt comparison, and counts every attempt
     * regardless of outcome — a successful login from a burst source is itself a
     * signal worth recording.
     *
     * @throws com.bhukkad.exception.FraudBlockedException as 429 when the source has
     *         exceeded the {@code auth-login} threshold within the detection window
     */
    @PostMapping("/login")
    @RateLimited("auth-login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest request) {
        fraudDetectionService.checkAndBlock(null, FraudEventTypes.AUTH_LOGIN);
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(ApiResponse.success("Login successful", response));
    }

    @PostMapping("/verify-email")
    public ResponseEntity<ApiResponse<Void>> verifyEmail(@RequestHeader("Authorization") String authHeader, @RequestParam String email) {
        String token = RequestUtils.extractTokenFromRequestHeaders(authHeader);
        authService.verifyEmail(email, token);
        return ResponseEntity.ok(ApiResponse.success("Email verified successfully", null));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse<Void>> forgotPassword(@RequestParam String email) {
        authService.forgotPassword(email);
        return ResponseEntity.ok(ApiResponse.success("Password reset link sent to email", null));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse<Void>> resetPassword(@RequestParam String token, @RequestParam String newPassword) {
        authService.resetPassword(token, newPassword);
        return ResponseEntity.ok(ApiResponse.success("Password reset successful", null));
    }

    @PostMapping("/change-password")
    @Operation(summary = "Change password")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam String oldPassword,
            @RequestParam String newPassword) {

        String token = RequestUtils.extractTokenFromRequestHeaders(authHeader);
        authService.changePassword(token, oldPassword, newPassword);
        return ResponseEntity.ok(ApiResponse.success("Password changed successfully", null));
    }

    @PostMapping("/refresh-token")
    @Operation(summary = "Refresh token")
    public ResponseEntity<ApiResponse<AuthResponse>> refreshToken(
            @RequestHeader("Authorization") String authHeader) {

        String token = RequestUtils.extractTokenFromRequestHeaders(authHeader);
        AuthResponse response = authService.refreshToken(token);
        return ResponseEntity.ok(ApiResponse.success("Token refreshed", response));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<String>> logout(@RequestHeader("Authorization") String authHeader) {
        String token = RequestUtils.extractTokenFromRequestHeaders(authHeader);
        authService.logout(token);
        return ResponseEntity.ok(ApiResponse.success("Logger out successfully"));
    }
}