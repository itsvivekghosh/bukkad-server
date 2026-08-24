package com.bhukkad.service;

import com.bhukkad.dto.request.LoginRequest;
import com.bhukkad.dto.request.RegisterRequest;
import com.bhukkad.dto.response.AuthResponse;

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
}