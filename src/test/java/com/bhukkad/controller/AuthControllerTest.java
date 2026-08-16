package com.bhukkad.controller;

import com.bhukkad.dto.request.LoginRequest;
import com.bhukkad.dto.request.RegisterRequest;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.AuthResponse;
import com.bhukkad.fraud.FraudDetectionService;
import com.bhukkad.service.AuthService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private AuthService authService;

    /**
     * V17 fraud enforcement: {@code register} and {@code login} call
     * {@link FraudDetectionService#checkAndBlock(Long, String)} before delegating to
     * {@link AuthService}, so the mock must exist even though the default no-op return is
     * exactly the "not blocked" path these tests want. The remaining endpoints never reach it.
     */
    @Mock
    private FraudDetectionService fraudDetectionService;

    @InjectMocks
    private AuthController authController;

    @Test
    void register_returnsSuccess() {
        RegisterRequest request = new RegisterRequest();
        AuthResponse authResponse = AuthResponse.builder().token("jwt").email("a@b.com").build();
        when(authService.register(request)).thenReturn(authResponse);

        ResponseEntity<ApiResponse<AuthResponse>> response = authController.register(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().isSuccess());
        assertEquals("Registration successful", response.getBody().getMessage());
        assertEquals(authResponse, response.getBody().getData());
        verify(authService).register(request);
    }

    @Test
    void login_returnsSuccess() {
        LoginRequest request = new LoginRequest();
        AuthResponse authResponse = AuthResponse.builder().token("jwt").build();
        when(authService.login(request)).thenReturn(authResponse);

        ResponseEntity<ApiResponse<AuthResponse>> response = authController.login(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Login successful", response.getBody().getMessage());
        assertEquals(authResponse, response.getBody().getData());
        verify(authService).login(request);
    }

    @Test
    void verifyEmail_extractsBearerToken() {
        ResponseEntity<ApiResponse<Void>> response = authController.verifyEmail("Bearer tok", "user@test.com");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Email verified successfully", response.getBody().getMessage());
        verify(authService).verifyEmail("user@test.com", "tok");
    }

    @Test
    void forgotPassword_returnsSuccess() {
        ResponseEntity<ApiResponse<Void>> response = authController.forgotPassword("user@test.com");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Password reset link sent to email", response.getBody().getMessage());
        verify(authService).forgotPassword("user@test.com");
    }

    @Test
    void resetPassword_returnsSuccess() {
        ResponseEntity<ApiResponse<Void>> response = authController.resetPassword("reset-tok", "newPass");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Password reset successful", response.getBody().getMessage());
        verify(authService).resetPassword("reset-tok", "newPass");
    }

    @Test
    void changePassword_extractsBearerToken() {
        ResponseEntity<ApiResponse<Void>> response =
                authController.changePassword("Bearer tok", "old", "new");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Password changed successfully", response.getBody().getMessage());
        verify(authService).changePassword("tok", "old", "new");
    }

    @Test
    void refreshToken_extractsBearerToken() {
        AuthResponse authResponse = AuthResponse.builder().token("new-jwt").build();
        when(authService.refreshToken("tok")).thenReturn(authResponse);

        ResponseEntity<ApiResponse<AuthResponse>> response = authController.refreshToken("Bearer tok");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Token refreshed", response.getBody().getMessage());
        assertEquals(authResponse, response.getBody().getData());
        verify(authService).refreshToken("tok");
    }

    @Test
    void logout_extractsBearerToken() {
        ResponseEntity<ApiResponse<String>> response = authController.logout("Bearer tok");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().isSuccess());
        assertEquals("Logger out successfully", response.getBody().getData());
        verify(authService).logout("tok");
    }
}
