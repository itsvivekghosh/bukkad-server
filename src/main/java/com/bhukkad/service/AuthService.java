package com.bhukkad.service;

import com.bhukkad.dto.request.CompleteProfileRequest;
import com.bhukkad.dto.request.LoginRequest;
import com.bhukkad.dto.request.OtpVerifyRequest;
import com.bhukkad.dto.request.PhoneRegisterRequest;
import com.bhukkad.dto.request.PhoneSendOtpRequest;
import com.bhukkad.dto.request.RefreshTokenRequest;
import com.bhukkad.dto.request.RegisterRequest;
import com.bhukkad.dto.response.AuthResponse;
import com.bhukkad.dto.response.PhoneRegisterResponse;
import com.bhukkad.dto.response.PhoneSendOtpResponse;

public interface AuthService {
    AuthResponse register(RegisterRequest request);
    AuthResponse login(LoginRequest request);
    void verifyEmail(String email, String token);
    void forgotPassword(String email);
    void resetPassword(String token, String newPassword);
    AuthResponse refreshToken(String token);
    void changePassword(String token, String oldPassword, String newPassword);
    void logout(String token);

    AuthResponse verifyMfaLogin(String mfaToken, String totpCode);

    // ---- Phone-first registration ----
    PhoneRegisterResponse registerPhone(PhoneRegisterRequest request);
    AuthResponse verifyPhone(OtpVerifyRequest request);
    void resendPhoneOtp(String phoneNumber, String channel);
    void completeProfile(Long userId, CompleteProfileRequest request);

    // ---- Unified phone sign-in (create-or-login) ----
    PhoneSendOtpResponse sendPhoneLoginOtp(PhoneSendOtpRequest request);
    AuthResponse verifyPhoneLogin(OtpVerifyRequest request);
}
