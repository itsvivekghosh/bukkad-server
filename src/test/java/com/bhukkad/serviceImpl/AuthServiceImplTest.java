package com.bhukkad.serviceImpl;

import com.bhukkad.dto.request.LoginRequest;
import com.bhukkad.dto.request.RegisterRequest;
import com.bhukkad.dto.response.AuthResponse;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.DeliveryAgent;
import com.bhukkad.entity.RestaurantOwner;
import com.bhukkad.entity.User;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.logging.SecurityEventLogger;
import com.bhukkad.repository.CustomerRepository;
import com.bhukkad.repository.DeliveryAgentRepository;
import com.bhukkad.repository.RestaurantOwnerRepository;
import com.bhukkad.repository.UserRepository;
import com.bhukkad.referral.ReferralService;
import com.bhukkad.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private RestaurantOwnerRepository restaurantOwnerRepository;
    @Mock
    private DeliveryAgentRepository deliveryAgentRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtTokenProvider jwtTokenProvider;
    @Mock
    private AuthenticationManager authenticationManager;
    @Mock
    private SecurityEventLogger securityEventLogger;
    @Mock
    private com.bhukkad.security.AuthTokenService authTokenService;
    @Mock
    private com.bhukkad.service.NotificationService notificationService;
    @Mock
    private ReferralService referralService;

    @InjectMocks
    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        lenient().when(passwordEncoder.encode(anyString())).thenReturn("encoded-password");
        lenient().when(jwtTokenProvider.generateAccessToken(any(UserDetails.class))).thenReturn("access-token");
        lenient().when(jwtTokenProvider.generateRefreshToken(any(UserDetails.class))).thenReturn("refresh-token");
        lenient().when(jwtTokenProvider.getRemainingValidityMs(anyString())).thenReturn(3600000L);
    }

    private RegisterRequest registerRequest(User.UserRole role) {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("user@example.com");
        request.setPassword("secret1");
        request.setFullName("Test User");
        request.setPhoneNumber("9876543210");
        request.setRole(role);
        return request;
    }

    private User activeUser() {
        User user = new User();
        user.setId(1L);
        user.setEmail("user@example.com");
        user.setPassword("encoded-password");
        user.setFullName("Test User");
        user.setRole(User.UserRole.CUSTOMER);
        user.setActive(true);
        user.setEmailVerified(false);
        return user;
    }

    @Test
    void register_emailExists_throwsBusinessException() {
        RegisterRequest request = registerRequest(User.UserRole.CUSTOMER);
        when(userRepository.existsByEmail(request.getEmail())).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class, () -> authService.register(request));
        assertEquals("Email already exists", ex.getMessage());
        verify(customerRepository, never()).save(any());
    }

    @Test
    void register_phoneExists_throwsBusinessException() {
        RegisterRequest request = registerRequest(User.UserRole.CUSTOMER);
        when(userRepository.existsByEmail(request.getEmail())).thenReturn(false);
        when(userRepository.existsByPhoneNumber(request.getPhoneNumber())).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class, () -> authService.register(request));
        assertEquals("Phone number already exists", ex.getMessage());
    }

    @Test
    void register_phoneNull_skipsPhoneUniquenessCheck() {
        RegisterRequest request = registerRequest(User.UserRole.CUSTOMER);
        request.setPhoneNumber(null);
        when(userRepository.existsByEmail(request.getEmail())).thenReturn(false);
        when(customerRepository.save(any(Customer.class))).thenAnswer(inv -> {
            Customer c = inv.getArgument(0);
            c.setId(1L);
            return c;
        });

        AuthResponse response = authService.register(request);

        assertEquals("access-token", response.getToken());
        verify(userRepository, never()).existsByPhoneNumber(any());
        verify(securityEventLogger).logRegistration(1L, "user@example.com", "CUSTOMER");
    }

    @Test
    void register_customer_success() {
        RegisterRequest request = registerRequest(User.UserRole.CUSTOMER);
        when(userRepository.existsByEmail(request.getEmail())).thenReturn(false);
        when(userRepository.existsByPhoneNumber(request.getPhoneNumber())).thenReturn(false);
        when(customerRepository.save(any(Customer.class))).thenAnswer(inv -> {
            Customer c = inv.getArgument(0);
            c.setId(10L);
            return c;
        });

        AuthResponse response = authService.register(request);

        assertEquals("access-token", response.getToken());
        assertEquals("Bearer", response.getTokenType());
        assertEquals(10L, response.getUserId());
        assertEquals("user@example.com", response.getEmail());
        assertEquals("Test User", response.getFullName());
        assertEquals("CUSTOMER", response.getRole());
        verify(customerRepository).save(any(Customer.class));
        verify(passwordEncoder).encode("secret1");
        verify(jwtTokenProvider).generateAccessToken(any(UserDetails.class));
        verify(jwtTokenProvider).generateRefreshToken(any(UserDetails.class));
        verify(securityEventLogger).logRegistration(10L, "user@example.com", "CUSTOMER");
    }

    @Test
    void register_restaurantOwner_setsVerifiedTrue() {
        RegisterRequest request = registerRequest(User.UserRole.RESTAURANT_OWNER);
        when(userRepository.existsByEmail(request.getEmail())).thenReturn(false);
        when(userRepository.existsByPhoneNumber(request.getPhoneNumber())).thenReturn(false);
        when(restaurantOwnerRepository.save(any(RestaurantOwner.class))).thenAnswer(inv -> {
            RestaurantOwner owner = inv.getArgument(0);
            owner.setId(20L);
            assertTrue(owner.getVerified());
            assertEquals(User.UserRole.RESTAURANT_OWNER, owner.getRole());
            return owner;
        });

        AuthResponse response = authService.register(request);

        assertEquals(20L, response.getUserId());
        assertEquals("RESTAURANT_OWNER", response.getRole());
        verify(securityEventLogger).logRegistration(20L, "user@example.com", "RESTAURANT_OWNER");
    }

    @Test
    void register_deliveryAgent_success() {
        RegisterRequest request = registerRequest(User.UserRole.DELIVERY_AGENT);
        when(userRepository.existsByEmail(request.getEmail())).thenReturn(false);
        when(userRepository.existsByPhoneNumber(request.getPhoneNumber())).thenReturn(false);
        when(deliveryAgentRepository.save(any(DeliveryAgent.class))).thenAnswer(inv -> {
            DeliveryAgent agent = inv.getArgument(0);
            agent.setId(30L);
            assertEquals(User.UserRole.DELIVERY_AGENT, agent.getRole());
            assertTrue(agent.getActive());
            return agent;
        });

        AuthResponse response = authService.register(request);

        assertEquals(30L, response.getUserId());
        assertEquals("DELIVERY_AGENT", response.getRole());
        verify(securityEventLogger).logRegistration(30L, "user@example.com", "DELIVERY_AGENT");
    }

    @Test
    void register_adminRole_throwsInvalidUserRole() {
        RegisterRequest request = registerRequest(User.UserRole.ADMIN);
        when(userRepository.existsByEmail(request.getEmail())).thenReturn(false);
        when(userRepository.existsByPhoneNumber(request.getPhoneNumber())).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, () -> authService.register(request));
        assertEquals("Invalid user role", ex.getMessage());
    }

    @Test
    void register_nullRole_throwsNullPointerException() {
        RegisterRequest request = registerRequest(null);
        when(userRepository.existsByEmail(request.getEmail())).thenReturn(false);
        when(userRepository.existsByPhoneNumber(request.getPhoneNumber())).thenReturn(false);

        assertThrows(NullPointerException.class, () -> authService.register(request));
    }

    @Test
    void login_success() {
        LoginRequest request = new LoginRequest();
        request.setEmail("user@example.com");
        request.setPassword("secret1");
        User user = activeUser();
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(org.mockito.Mockito.mock(Authentication.class));
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));

        AuthResponse response = authService.login(request);

        assertEquals("access-token", response.getToken());
        assertEquals(1L, response.getUserId());
        assertEquals("CUSTOMER", response.getRole());
        verify(securityEventLogger).logLoginSuccess(1L, "user@example.com", "CUSTOMER");
    }

    @Test
    void login_userNotFound_throwsBusinessException() {
        LoginRequest request = new LoginRequest();
        request.setEmail("missing@example.com");
        request.setPassword("secret1");
        when(authenticationManager.authenticate(any())).thenReturn(null);
        when(userRepository.findByEmail("missing@example.com")).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class, () -> authService.login(request));
        assertEquals("User not found", ex.getMessage());
    }

    @Test
    void login_inactiveAccount_throwsBusinessException() {
        LoginRequest request = new LoginRequest();
        request.setEmail("user@example.com");
        request.setPassword("secret1");
        User user = activeUser();
        user.setActive(false);
        when(authenticationManager.authenticate(any())).thenReturn(null);
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));

        BusinessException ex = assertThrows(BusinessException.class, () -> authService.login(request));
        assertEquals("Account is deactivated", ex.getMessage());
        verify(securityEventLogger).logLoginFailure("user@example.com", "Account deactivated");
    }

    @Test
    void login_badCredentials_isRethrownAfterLogging() {
        LoginRequest request = new LoginRequest();
        request.setEmail("user@example.com");
        request.setPassword("wrong");
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        BadCredentialsException ex = assertThrows(BadCredentialsException.class, () -> authService.login(request));
        assertEquals("Bad credentials", ex.getMessage());
        verify(securityEventLogger).logLoginFailure("user@example.com", "Invalid credentials");
    }

    @Test
    void verifyEmail_nullToken_throwsBusinessException() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.verifyEmail("user@example.com", null));
        assertEquals("Token is required", ex.getMessage());
    }

    @Test
    void verifyEmail_emptyToken_throwsBusinessException() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.verifyEmail("user@example.com", ""));
        assertEquals("Token is required", ex.getMessage());
    }

    @Test
    void verifyEmail_tokenMismatch_throwsUnauthorized() {
        when(jwtTokenProvider.extractUsername("token")).thenReturn("other@example.com");

        assertThrows(com.bhukkad.exception.UnauthorizedException.class,
                () -> authService.verifyEmail("user@example.com", "token"));
    }

    @Test
    void verifyEmail_invalidToken_throwsUnauthorized() {
        when(jwtTokenProvider.extractUsername("bad-token")).thenReturn("user@example.com");
        when(jwtTokenProvider.validateToken("bad-token")).thenReturn(false);

        assertThrows(com.bhukkad.exception.UnauthorizedException.class,
                () -> authService.verifyEmail("user@example.com", "bad-token"));
    }

    @Test
    void verifyEmail_matchingToken_success() {
        User user = activeUser();
        when(jwtTokenProvider.extractUsername("token")).thenReturn("user@example.com");
        when(jwtTokenProvider.validateToken("token")).thenReturn(true);
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);

        authService.verifyEmail("user@example.com", "token");

        assertTrue(user.getEmailVerified());
        verify(userRepository).save(user);
    }

    @Test
    void verifyEmail_userNotFound_throwsBusinessException() {
        when(jwtTokenProvider.extractUsername("token")).thenReturn("missing@example.com");
        when(jwtTokenProvider.validateToken("token")).thenReturn(true);
        when(userRepository.findByEmail("missing@example.com")).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.verifyEmail("missing@example.com", "token"));
        assertEquals("User not found", ex.getMessage());
    }

    @Test
    void verifyEmail_alreadyVerified_throwsBusinessException() {
        User user = activeUser();
        user.setEmailVerified(true);
        when(jwtTokenProvider.extractUsername("token")).thenReturn("user@example.com");
        when(jwtTokenProvider.validateToken("token")).thenReturn(true);
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.verifyEmail("user@example.com", "token"));
        assertEquals("Email already verified", ex.getMessage());
        verify(userRepository, never()).save(any());
    }

    @Test
    void forgotPassword_success() {
        User user = activeUser();
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(authTokenService.createPasswordResetToken(eq("user@example.com"), any())).thenReturn("reset-token");

        authService.forgotPassword("user@example.com");

        verify(notificationService).sendPasswordReset("user@example.com", "reset-token");
    }

    @Test
    void forgotPassword_userNotFound_throwsBusinessException() {
        when(userRepository.findByEmail("missing@example.com")).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.forgotPassword("missing@example.com"));
        assertEquals("User not found", ex.getMessage());
    }

    @Test
    void resetPassword_nullToken_throwsBusinessException() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.resetPassword(null, "newpass"));
        assertEquals("Token is required", ex.getMessage());
    }

    @Test
    void resetPassword_emptyToken_throwsBusinessException() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.resetPassword("", "newpass"));
        assertEquals("Token is required", ex.getMessage());
    }

    @Test
    void resetPassword_invalidToken_throwsBusinessException() {
        when(authTokenService.validatePasswordResetToken("token")).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.resetPassword("token", "newpass"));
        assertEquals("Invalid or expired reset token", ex.getMessage());
    }

    @Test
    void resetPassword_userNotFound_throwsBusinessException() {
        when(authTokenService.validatePasswordResetToken("token")).thenReturn("missing@example.com");
        when(userRepository.findByEmail("missing@example.com")).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.resetPassword("token", "newpass"));
        assertEquals("User not found", ex.getMessage());
    }

    @Test
    void resetPassword_success() {
        User user = activeUser();
        when(authTokenService.validatePasswordResetToken("token")).thenReturn("user@example.com");
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("newpass")).thenReturn("new-encoded");
        when(userRepository.save(user)).thenReturn(user);

        authService.resetPassword("token", "newpass");

        assertEquals("new-encoded", user.getPassword());
        verify(authTokenService).consumePasswordResetToken("token");
        verify(authTokenService).revokeAllRefreshTokens(1L);
        verify(securityEventLogger).logPasswordChange(1L, "user@example.com");
    }

    @Test
    void refreshToken_nullToken_throwsBusinessException() {
        BusinessException ex = assertThrows(BusinessException.class, () -> authService.refreshToken(null));
        assertEquals("Token is required", ex.getMessage());
    }

    @Test
    void refreshToken_emptyToken_throwsBusinessException() {
        BusinessException ex = assertThrows(BusinessException.class, () -> authService.refreshToken(""));
        assertEquals("Token is required", ex.getMessage());
    }

    @Test
    void refreshToken_userNotFound_throwsBusinessException() {
        when(jwtTokenProvider.validateToken("token")).thenReturn(true);
        when(jwtTokenProvider.isRefreshToken("token")).thenReturn(true);
        when(jwtTokenProvider.extractUsername("token")).thenReturn("missing@example.com");
        when(userRepository.findByEmail("missing@example.com")).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class, () -> authService.refreshToken("token"));
        assertEquals("User not found", ex.getMessage());
    }

    @Test
    void refreshToken_inactiveUser_throwsBusinessException() {
        User user = activeUser();
        user.setActive(false);
        when(jwtTokenProvider.validateToken("token")).thenReturn(true);
        when(jwtTokenProvider.isRefreshToken("token")).thenReturn(true);
        when(jwtTokenProvider.extractUsername("token")).thenReturn("user@example.com");
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));

        BusinessException ex = assertThrows(BusinessException.class, () -> authService.refreshToken("token"));
        assertEquals("Account is deactivated", ex.getMessage());
    }

    @Test
    void refreshToken_success() {
        User user = activeUser();
        when(jwtTokenProvider.validateToken("token")).thenReturn(true);
        when(jwtTokenProvider.isRefreshToken("token")).thenReturn(true);
        when(jwtTokenProvider.extractUsername("token")).thenReturn("user@example.com");
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(authTokenService.isRefreshTokenValid(1L, "token")).thenReturn(true);

        AuthResponse response = authService.refreshToken("token");

        assertEquals("access-token", response.getToken());
        assertEquals("refresh-token", response.getRefreshToken());
        assertEquals("Bearer", response.getTokenType());
        assertEquals(1L, response.getUserId());
        verify(jwtTokenProvider).generateAccessToken(any(UserDetails.class));
        verify(jwtTokenProvider).generateRefreshToken(any(UserDetails.class));
    }

    @Test
    void changePassword_nullToken_throwsBusinessException() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.changePassword(null, "old", "newpass"));
        assertEquals("Token is required", ex.getMessage());
    }

    @Test
    void changePassword_emptyToken_throwsBusinessException() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.changePassword("", "old", "newpass"));
        assertEquals("Token is required", ex.getMessage());
    }

    @Test
    void changePassword_userNotFound_throwsBusinessException() {
        when(jwtTokenProvider.extractUsername("token")).thenReturn("missing@example.com");
        when(userRepository.findByEmail("missing@example.com")).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.changePassword("token", "old", "newpass"));
        assertEquals("User not found", ex.getMessage());
    }

    @Test
    void changePassword_incorrectOldPassword_throwsBusinessException() {
        User user = activeUser();
        when(jwtTokenProvider.extractUsername("token")).thenReturn("user@example.com");
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "encoded-password")).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.changePassword("token", "wrong", "newpass"));
        assertEquals("Current password is incorrect", ex.getMessage());
    }

    @Test
    void changePassword_newPasswordTooShort_throwsBusinessException() {
        User user = activeUser();
        when(jwtTokenProvider.extractUsername("token")).thenReturn("user@example.com");
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("oldpass", "encoded-password")).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.changePassword("token", "oldpass", "12345"));
        assertEquals("New password must be at least 6 characters", ex.getMessage());
    }

    @Test
    void changePassword_sameAsOld_throwsBusinessException() {
        User user = activeUser();
        when(jwtTokenProvider.extractUsername("token")).thenReturn("user@example.com");
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("oldpass", "encoded-password")).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.changePassword("token", "oldpass", "oldpass"));
        assertEquals("New password must be different", ex.getMessage());
    }

    @Test
    void changePassword_success() {
        User user = activeUser();
        when(jwtTokenProvider.extractUsername("token")).thenReturn("user@example.com");
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("oldpass", "encoded-password")).thenReturn(true);
        when(passwordEncoder.encode("newpass")).thenReturn("new-encoded");
        when(userRepository.save(user)).thenReturn(user);

        authService.changePassword("token", "oldpass", "newpass");

        assertEquals("new-encoded", user.getPassword());
        verify(securityEventLogger).logPasswordChange(1L, "user@example.com");
    }

    @Test
    void logout_nullToken_succeeds() {
        authService.logout(null);
        verify(jwtTokenProvider, never()).extractUsername(any());
    }

    @Test
    void logout_emptyToken_succeeds() {
        authService.logout("");
        verify(jwtTokenProvider, never()).extractUsername(any());
    }

    @Test
    void logout_validToken_succeeds() {
        User user = activeUser();
        when(jwtTokenProvider.validateToken("token")).thenReturn(true);
        when(jwtTokenProvider.isRefreshToken("token")).thenReturn(false);
        when(jwtTokenProvider.extractUsername("token")).thenReturn("user@example.com");
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(jwtTokenProvider.getRemainingValidityMs("token")).thenReturn(1000L);

        authService.logout("token");

        verify(authTokenService).blacklistAccessToken("token", 1000L);
    }

    @Test
    void logout_extractUsernameThrows_stillSucceeds() {
        when(jwtTokenProvider.validateToken("bad")).thenReturn(true);
        when(jwtTokenProvider.extractUsername("bad")).thenThrow(new RuntimeException("expired"));

        authService.logout("bad");
    }

    @Test
    void logout_doThrowOnExtract_stillSucceeds() {
        when(jwtTokenProvider.validateToken("x")).thenReturn(true);
        doThrow(new IllegalArgumentException("malformed")).when(jwtTokenProvider).extractUsername(eq("x"));

        authService.logout("x");

        assertNotNull(authService);
    }
}
