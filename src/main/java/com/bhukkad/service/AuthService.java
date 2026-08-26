package com.bhukkad.service;

import com.bhukkad.dto.request.CompleteProfileRequest;
import com.bhukkad.dto.request.LoginRequest;
import com.bhukkad.dto.request.OtpVerifyRequest;
import com.bhukkad.dto.request.PhoneRegisterRequest;
import com.bhukkad.dto.request.RefreshTokenRequest;
import com.bhukkad.dto.request.RegisterRequest;
import com.bhukkad.dto.response.AuthResponse;
import com.bhukkad.dto.response.PhoneRegisterResponse;

public interface AuthService {
    AuthResponse register(RegisterRequest request);
    AuthResponse login(LoginRequest request);
    void verifyEmail(String email, String token);
    void forgotPassword(String email);
    void resetPassword(String token, String newPassword);
    AuthResponse refreshToken(String token);
    void changePassword(String token, String oldPassword, String newPassword);
    void logout(String token);

    /**
     * Verifies a TOTP code passed during login and issues the full token pair.
     *
     * @param mfaToken  short-lived token from the initial login response
     * @param totpCode  6-digit code from the authenticator app
     * @return the full AuthResponse with access/refresh tokens
     */
    AuthResponse verifyMfaLogin(String mfaToken, String totpCode);

    /**
     * Step 1 of phone-first registration: creates an account backed only by a
     * phone number, sends an OTP (SMS or WhatsApp), and returns metadata.
     * No JWT tokens are issued until the OTP is verified.
     */
    PhoneRegisterResponse registerPhone(PhoneRegisterRequest request);

    /**
     * Step 2 of phone-first registration: validates the OTP sent during
     * {@link #registerPhone} and, on success, issues the JWT token pair.
     */
    AuthResponse verifyPhone(OtpVerifyRequest request);

    /**
     * Resends a verification OTP to the same phone used during registration.
     */
    void resendPhoneOtp(String phoneNumber, String channel);

    /**
     * Step 3 of phone-first registration: completes the user's profile by
     * adding an email, full name, and password. The email is verified
     * asynchronously via {@link #verifyEmail}.
     */
    void completeProfile(Long userId, CompleteProfileRequest request);
}
