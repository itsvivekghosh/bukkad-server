package com.bhukkad.controller;

import com.bhukkad.config.ApiPaths;

import com.bhukkad.dto.request.ChangePasswordRequest;
import com.bhukkad.dto.request.CompleteProfileRequest;
import com.bhukkad.dto.request.ForgotPasswordRequest;
import com.bhukkad.dto.request.LoginRequest;
import com.bhukkad.dto.request.OtpResendRequest;
import com.bhukkad.dto.request.OtpVerifyRequest;
import com.bhukkad.dto.request.PhoneRegisterRequest;
import com.bhukkad.dto.request.PhoneSendOtpRequest;
import com.bhukkad.dto.request.RefreshTokenRequest;
import com.bhukkad.dto.request.RegisterRequest;
import com.bhukkad.dto.request.ResetPasswordRequest;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.AuthResponse;
import com.bhukkad.dto.response.EncryptionKeyResponse;
import com.bhukkad.dto.response.PhoneRegisterResponse;
import com.bhukkad.dto.response.PhoneSendOtpResponse;
import com.bhukkad.fraud.FraudDetectionService;
import com.bhukkad.fraud.FraudEventTypes;
import com.bhukkad.ratelimit.RateLimited;
import com.bhukkad.security.ClientEncryptionKeyService;
import com.bhukkad.security.JwePasswordCrypto;
import com.bhukkad.security.ReplayNonceValidator;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.service.AuthService;
import com.bhukkad.common.web.RequestUtils;
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
    private final SecurityUtils securityUtils;
    private final ClientEncryptionKeyService encryptionKeyService;
    private final JwePasswordCrypto jwePasswordCrypto;
    private final ReplayNonceValidator replayNonceValidator;

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
        resolveEncryptedPassword(request);
        AuthResponse response = authService.register(request);
        return ResponseEntity.ok(ApiResponse.success("Registration successful", response));
    }

    /**
     * Returns the server's RSA public key (Base64-encoded X.509 SPKI) for
     * client-side password encryption. The key is valid for 24 hours; clients
     * should cache and refresh at least once per day.
     *
     * <p>This endpoint is public and rate-limited to prevent key enumeration abuse.
     */
    @GetMapping("/encryption-key")
    @RateLimited("auth-login")
    @Operation(summary = "Get RSA public key for client-side password encryption")
    public ResponseEntity<ApiResponse<EncryptionKeyResponse>> getEncryptionKey() {
        EncryptionKeyResponse response = new EncryptionKeyResponse(
                encryptionKeyService.getPublicKeyJwk(),
                encryptionKeyService.keyExpiryEpochSecond());
        return ResponseEntity.ok(ApiResponse.success("Encryption key retrieved", response));
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
        resolveEncryptedPassword(request);
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(ApiResponse.success("Login successful", response));
    }

    /**
     * Completes a login for a TOTP-enabled privileged account by verifying the
     * second factor. The {@code mfaToken} comes from the initial login response.
     */
    @PostMapping("/mfa/verify")
    @RateLimited("auth-login")
    public ResponseEntity<ApiResponse<AuthResponse>> verifyMfaLogin(
            @RequestParam String mfaToken,
            @RequestParam String code) {
        AuthResponse response = authService.verifyMfaLogin(mfaToken, code);
        return ResponseEntity.ok(ApiResponse.success("MFA verification successful", response));
    }

    @PostMapping("/verify-email")
    public ResponseEntity<ApiResponse<Void>> verifyEmail(@RequestHeader("Authorization") String authHeader, @RequestParam String email) {
        String token = RequestUtils.extractTokenFromRequestHeaders(authHeader);
        authService.verifyEmail(email, token);
        return ResponseEntity.ok(ApiResponse.success("Email verified successfully", null));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse<Void>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request.getEmail());
        return ResponseEntity.ok(ApiResponse.success("Password reset link sent to email", null));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse<Void>> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request.getToken(), request.getNewPassword());
        return ResponseEntity.ok(ApiResponse.success("Password reset successful", null));
    }

    @PostMapping("/change-password")
    @Operation(summary = "Change password")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody ChangePasswordRequest request) {

        String token = RequestUtils.extractTokenFromRequestHeaders(authHeader);
        authService.changePassword(token, request.getOldPassword(), request.getNewPassword());
        return ResponseEntity.ok(ApiResponse.success("Password changed successfully", null));
    }

    @PostMapping("/refresh-token")
    @Operation(summary = "Refresh token")
    public ResponseEntity<ApiResponse<AuthResponse>> refreshToken(
            @Valid @RequestBody RefreshTokenRequest request) {

        AuthResponse response = authService.refreshToken(request.getRefreshToken());
        return ResponseEntity.ok(ApiResponse.success("Token refreshed", response));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<String>> logout(@RequestHeader("Authorization") String authHeader) {
        String token = RequestUtils.extractTokenFromRequestHeaders(authHeader);
        authService.logout(token);
        return ResponseEntity.ok(ApiResponse.success("Logger out successfully"));
    }

    // ------------------------------------------------------------------
    // Phone-first registration
    // ------------------------------------------------------------------

    /**
     * Step 1: Register with only a phone number. Creates an account with a
     * placeholder email and sends a 6-digit OTP via SMS or WhatsApp.
     * No JWT tokens are issued until {@link #verifyPhone} succeeds.
     */
    @PostMapping("/register/phone")
    @RateLimited("auth-register")
    @Operation(summary = "Register with phone number (step 1 of phone-first registration)")
    public ResponseEntity<ApiResponse<PhoneRegisterResponse>> registerPhone(
            @Valid @RequestBody PhoneRegisterRequest request) {
        fraudDetectionService.checkAndBlock(null, FraudEventTypes.AUTH_REGISTER);
        PhoneRegisterResponse response = authService.registerPhone(request);
        return ResponseEntity.ok(ApiResponse.success("OTP sent. Please verify your phone number.", response));
    }

    /**
     * Resend the verification OTP for a phone-first registration.
     */
    @PostMapping("/register/phone/resend")
    @RateLimited("auth-register")
    @Operation(summary = "Resend phone verification OTP")
    public ResponseEntity<ApiResponse<Void>> resendPhoneOtp(
            @Valid @RequestBody OtpResendRequest request) {
        authService.resendPhoneOtp(request.getPhoneNumber(),
                request.getChannel() != null ? request.getChannel() : "sms");
        return ResponseEntity.ok(ApiResponse.success("OTP resent", null));
    }

    /**
     * Step 2: Verify the 6-digit OTP and receive JWT tokens.
     */
    @PostMapping("/verify-phone")
    @RateLimited("auth-login")
    @Operation(summary = "Verify phone OTP and receive tokens (step 2 of phone-first registration)")
    public ResponseEntity<ApiResponse<AuthResponse>> verifyPhone(
            @Valid @RequestBody OtpVerifyRequest request) {
        AuthResponse response = authService.verifyPhone(request);
        return ResponseEntity.ok(ApiResponse.success("Phone verified successfully", response));
    }

    /**
     * Step 3: Complete profile by adding email, full name, and password.
     * Requires a valid access token from the phone verification step.
     */
    @PutMapping("/profile/complete")
    @Operation(summary = "Complete profile after phone-first registration (step 3)")
    public ResponseEntity<ApiResponse<Void>> completeProfile(
            @Valid @RequestBody CompleteProfileRequest request) {
        Long userId = securityUtils.getCurrentUserId();
        authService.completeProfile(userId, request);
        return ResponseEntity.ok(ApiResponse.success("Profile completed successfully", null));
    }

    // ------------------------------------------------------------------
    // Unified phone sign-in (create-or-login)
    // ------------------------------------------------------------------

    /**
     * Send an OTP to a phone number for sign-in. Works for both new and
     * existing accounts — {@code isNewUser} in the response tells the caller
     * which case applies. The OTP is delivered instantly via SMS or WhatsApp.
     */
    @PostMapping("/phone/send-otp")
    @RateLimited("auth-login")
    @Operation(summary = "Send phone sign-in OTP (SMS/WhatsApp) — create-or-login")
    public ResponseEntity<ApiResponse<PhoneSendOtpResponse>> sendPhoneLoginOtp(
            @Valid @RequestBody PhoneSendOtpRequest request) {
        fraudDetectionService.checkAndBlock(null, FraudEventTypes.AUTH_LOGIN);
        PhoneSendOtpResponse response = authService.sendPhoneLoginOtp(request);
        return ResponseEntity.ok(ApiResponse.success("OTP sent", response));
    }

    /**
     * Verify the phone sign-in OTP. Creates a new account when the phone is
     * not registered yet, otherwise signs in the existing user, and issues the
     * JWT token pair in both cases.
     */
    @PostMapping("/phone/verify")
    @RateLimited("auth-login")
    @Operation(summary = "Verify phone sign-in OTP — creates or logs in the user")
    public ResponseEntity<ApiResponse<AuthResponse>> verifyPhoneLogin(
            @Valid @RequestBody OtpVerifyRequest request) {
        AuthResponse response = authService.verifyPhoneLogin(request);
        return ResponseEntity.ok(ApiResponse.success("Sign-in successful", response));
    }

    /**
     * Decrypts an encrypted password field (if present) on a request object
     * that has a {@code setEncryptedPassword} and {@code setPassword} method.
     * Also validates the nonce to prevent replay attacks.
     */
    private void resolveEncryptedPassword(Object request) {
        if (request instanceof LoginRequest lr) {
            if (lr.getEncryptedPassword() != null) {
                var decrypted = jwePasswordCrypto.decrypt(lr.getEncryptedPassword());
                replayNonceValidator.validateNonce(decrypted.nonce());
                lr.setPassword(decrypted.password());
            }
        } else if (request instanceof RegisterRequest rr) {
            if (rr.getEncryptedPassword() != null) {
                var decrypted = jwePasswordCrypto.decrypt(rr.getEncryptedPassword());
                replayNonceValidator.validateNonce(decrypted.nonce());
                rr.setPassword(decrypted.password());
            }
        }
    }
}