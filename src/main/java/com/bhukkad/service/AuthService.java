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
}