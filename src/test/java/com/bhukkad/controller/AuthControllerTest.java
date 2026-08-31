package com.bhukkad.controller;

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
import com.bhukkad.dto.response.PhoneRegisterResponse;
import com.bhukkad.dto.response.PhoneSendOtpResponse;
import com.bhukkad.fraud.FraudDetectionService;
import com.bhukkad.security.ClientEncryptionKeyService;
import com.bhukkad.security.JwePasswordCrypto;
import com.bhukkad.security.ReplayNonceValidator;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.service.AuthService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Tag;

@Tag("regression")
@ExtendWith(MockitoExtension.class)
public class AuthControllerTest {

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

    @Mock
    private SecurityUtils securityUtils;

    @Mock
    private ClientEncryptionKeyService encryptionKeyService;

    @Mock
    private JwePasswordCrypto jwePasswordCrypto;

    @Mock
    private ReplayNonceValidator replayNonceValidator;

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
    void getEncryptionKey_returnsPublicKeyAndExpiry() {
        when(encryptionKeyService.getPublicKeyJwk()).thenReturn("{\"kty\":\"RSA\",\"n\":\"abc\",\"e\":\"AQAB\"}");
        when(encryptionKeyService.keyExpiryEpochSecond()).thenReturn(9999999999L);

        ResponseEntity<ApiResponse<com.bhukkad.dto.response.EncryptionKeyResponse>> response =
                authController.getEncryptionKey();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Encryption key retrieved", response.getBody().getMessage());
        assertEquals("{\"kty\":\"RSA\",\"n\":\"abc\",\"e\":\"AQAB\"}",
                response.getBody().getData().getPublicKey());
        assertEquals(9999999999L, response.getBody().getData().getExpiresAt());
    }

    @Test
    void login_encryptedPassword_decryptsBeforeServiceCall() {
        LoginRequest request = new LoginRequest();
        request.setEncryptedPassword("jwe-string-here");
        request.setEmail("test@example.com");
        // Simulate decryption returning password + nonce
        when(jwePasswordCrypto.decrypt("jwe-string-here"))
                .thenReturn(new JwePasswordCrypto.DecryptedPayload("plaintext", "test-nonce", 1234567890L));
        AuthResponse authResponse = AuthResponse.builder().token("jwt").build();
        when(authService.login(request)).thenReturn(authResponse);

        ResponseEntity<ApiResponse<AuthResponse>> response = authController.login(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(jwePasswordCrypto).decrypt("jwe-string-here");
        verify(replayNonceValidator).validateNonce("test-nonce");
        verify(authService).login(request);
        assertEquals("plaintext", request.getPassword());
    }

    @Test
    void login_plaintextPassword_skipsDecryption() {
        LoginRequest request = new LoginRequest();
        request.setPassword("plaintext");
        request.setEmail("test@example.com");
        AuthResponse authResponse = AuthResponse.builder().token("jwt").build();
        when(authService.login(request)).thenReturn(authResponse);

        ResponseEntity<ApiResponse<AuthResponse>> response = authController.login(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(jwePasswordCrypto, never()).decrypt(anyString());
        verify(replayNonceValidator, never()).validateNonce(anyString());
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
        ForgotPasswordRequest request = new ForgotPasswordRequest();
        request.setEmail("user@test.com");
        ResponseEntity<ApiResponse<Void>> response = authController.forgotPassword(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Password reset link sent to email", response.getBody().getMessage());
        verify(authService).forgotPassword("user@test.com");
    }

    @Test
    void resetPassword_returnsSuccess() {
        ResetPasswordRequest request = new ResetPasswordRequest();
        request.setToken("reset-tok");
        request.setNewPassword("newPass");
        ResponseEntity<ApiResponse<Void>> response = authController.resetPassword(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Password reset successful", response.getBody().getMessage());
        verify(authService).resetPassword("reset-tok", "newPass");
    }

    @Test
    void changePassword_extractsBearerToken() {
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setOldPassword("old");
        request.setNewPassword("new");
        ResponseEntity<ApiResponse<Void>> response =
                authController.changePassword("Bearer tok", request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Password changed successfully", response.getBody().getMessage());
        verify(authService).changePassword("tok", "old", "new");
    }

    @Test
    void refreshToken_readsRefreshTokenFromBody() {
        AuthResponse authResponse = AuthResponse.builder().token("new-jwt").build();
        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken("refresh-tok");
        when(authService.refreshToken("refresh-tok")).thenReturn(authResponse);

        ResponseEntity<ApiResponse<AuthResponse>> response = authController.refreshToken(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Token refreshed", response.getBody().getMessage());
        assertEquals(authResponse, response.getBody().getData());
        verify(authService).refreshToken("refresh-tok");
    }

    @Test
    void logout_extractsBearerToken() {
        ResponseEntity<ApiResponse<String>> response = authController.logout("Bearer tok");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().isSuccess());
        assertEquals("Logger out successfully", response.getBody().getData());
        verify(authService).logout("tok");
    }

    // ------------------------------------------------------------------
    // Phone-first registration tests
    // ------------------------------------------------------------------

    @Test
    void registerPhone_returnsPhoneRegisterResponse() {
        PhoneRegisterRequest request = new PhoneRegisterRequest();
        request.setPhoneNumber("9876543210");

        PhoneRegisterResponse serviceResponse = PhoneRegisterResponse.builder()
                .phoneNumber("9876543210")
                .message("OTP sent")
                .otpExpiryMinutes(10)
                .build();
        when(authService.registerPhone(request)).thenReturn(serviceResponse);

        ResponseEntity<ApiResponse<PhoneRegisterResponse>> response =
                authController.registerPhone(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().isSuccess());
        assertEquals("OTP sent. Please verify your phone number.", response.getBody().getMessage());
        assertEquals(serviceResponse, response.getBody().getData());
        verify(authService).registerPhone(request);
        verify(fraudDetectionService).checkAndBlock(null, com.bhukkad.fraud.FraudEventTypes.AUTH_REGISTER);
    }

    @Test
    void resendPhoneOtp_returnsSuccess() {
        OtpResendRequest request = new OtpResendRequest();
        request.setPhoneNumber("9876543210");
        request.setChannel("sms");

        ResponseEntity<ApiResponse<Void>> response = authController.resendPhoneOtp(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("OTP resent", response.getBody().getMessage());
        verify(authService).resendPhoneOtp("9876543210", "sms");
    }

    @Test
    void resendPhoneOtp_defaultsChannelToSmsWhenNull() {
        OtpResendRequest request = new OtpResendRequest();
        request.setPhoneNumber("9876543210");

        ResponseEntity<ApiResponse<Void>> response = authController.resendPhoneOtp(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(authService).resendPhoneOtp("9876543210", "sms");
    }

    @Test
    void verifyPhone_returnsAuthResponse() {
        OtpVerifyRequest request = new OtpVerifyRequest();
        request.setPhoneNumber("9876543210");
        request.setCode("123456");

        AuthResponse authResponse = AuthResponse.builder().token("jwt").build();
        when(authService.verifyPhone(request)).thenReturn(authResponse);

        ResponseEntity<ApiResponse<AuthResponse>> response =
                authController.verifyPhone(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Phone verified successfully", response.getBody().getMessage());
        assertEquals(authResponse, response.getBody().getData());
        verify(authService).verifyPhone(request);
    }

    @Test
    void completeProfile_delegatesToServiceWithCurrentUserId() {
        CompleteProfileRequest request = new CompleteProfileRequest();
        request.setEmail("user@example.com");
        request.setFullName("Test User");

        when(securityUtils.getCurrentUserId()).thenReturn(42L);

        ResponseEntity<ApiResponse<Void>> response =
                authController.completeProfile(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Profile completed successfully", response.getBody().getMessage());
        verify(authService).completeProfile(42L, request);
    }

    // ------------------------------------------------------------------
    // Unified phone sign-in
    // ------------------------------------------------------------------

    @Test
    void sendPhoneLoginOtp_returnsPhoneSendOtpResponse() {
        PhoneSendOtpRequest request = new PhoneSendOtpRequest();
        request.setPhoneNumber("9876543210");
        request.setChannel("whatsapp");

        PhoneSendOtpResponse serviceResponse = PhoneSendOtpResponse.builder()
                .phoneNumber("9876543210")
                .otpExpiryMinutes(10)
                .isNewUser(true)
                .build();
        when(authService.sendPhoneLoginOtp(request)).thenReturn(serviceResponse);

        ResponseEntity<ApiResponse<PhoneSendOtpResponse>> response =
                authController.sendPhoneLoginOtp(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("9876543210", response.getBody().getData().getPhoneNumber());
        assertTrue(response.getBody().getData().isNewUser());
        verify(authService).sendPhoneLoginOtp(request);
    }

    @Test
    void verifyPhoneLogin_returnsAuthResponse() {
        OtpVerifyRequest request = new OtpVerifyRequest();
        request.setPhoneNumber("9876543210");
        request.setCode("123456");

        AuthResponse authResponse = AuthResponse.builder().token("jwt").isNewUser(true).build();
        when(authService.verifyPhoneLogin(request)).thenReturn(authResponse);

        ResponseEntity<ApiResponse<AuthResponse>> response =
                authController.verifyPhoneLogin(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Sign-in successful", response.getBody().getMessage());
        assertTrue(response.getBody().getData().isNewUser());
        verify(authService).verifyPhoneLogin(request);
    }
}
