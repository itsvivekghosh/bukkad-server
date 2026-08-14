package com.bhukkad.serviceImpl;

import com.bhukkad.dto.request.LoginRequest;
import com.bhukkad.dto.request.RegisterRequest;
import com.bhukkad.dto.response.AuthResponse;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.DeliveryAgent;
import com.bhukkad.entity.RestaurantOwner;
import com.bhukkad.entity.User;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.UnauthorizedException;
import com.bhukkad.logging.LoggingConstants;
import com.bhukkad.logging.SecurityEventLogger;
import com.bhukkad.repository.CustomerRepository;
import com.bhukkad.repository.DeliveryAgentRepository;
import com.bhukkad.repository.RestaurantOwnerRepository;
import com.bhukkad.repository.UserRepository;
import com.bhukkad.security.AuthTokenService;
import com.bhukkad.security.JwtTokenProvider;
import com.bhukkad.service.AuthService;
import com.bhukkad.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);
    private static final Duration RESET_TOKEN_TTL = Duration.ofMinutes(30);

    private final UserRepository userRepository;
    private final CustomerRepository customerRepository;
    private final RestaurantOwnerRepository restaurantOwnerRepository;
    private final DeliveryAgentRepository deliveryAgentRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final AuthenticationManager authenticationManager;
    private final SecurityEventLogger securityEventLogger;
    private final AuthTokenService authTokenService;
    private final NotificationService notificationService;

    @Override
    public AuthResponse register(RegisterRequest request) {
        log.info("Registration attempt | Email: {} | Role: {}", request.getEmail(), request.getRole());

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new BusinessException("Email already exists");
        }

        if (request.getPhoneNumber() != null &&
                userRepository.existsByPhoneNumber(request.getPhoneNumber())) {
            throw new BusinessException("Phone number already exists");
        }

        User user;

        switch (request.getRole()) {
            case CUSTOMER:
                Customer customer = new Customer();
                customer.setEmail(request.getEmail());
                customer.setPassword(passwordEncoder.encode(request.getPassword()));
                customer.setFullName(request.getFullName());
                customer.setPhoneNumber(request.getPhoneNumber());
                customer.setRole(User.UserRole.CUSTOMER);
                customer.setActive(true);
                user = customerRepository.save(customer);
                break;

            case RESTAURANT_OWNER:
                RestaurantOwner owner = new RestaurantOwner();
                owner.setEmail(request.getEmail());
                owner.setPassword(passwordEncoder.encode(request.getPassword()));
                owner.setFullName(request.getFullName());
                owner.setPhoneNumber(request.getPhoneNumber());
                owner.setRole(User.UserRole.RESTAURANT_OWNER);
                owner.setActive(true);
                owner.setVerified(true);
                user = restaurantOwnerRepository.save(owner);
                break;

            case DELIVERY_AGENT:
                DeliveryAgent agent = new DeliveryAgent();
                agent.setEmail(request.getEmail());
                agent.setPassword(passwordEncoder.encode(request.getPassword()));
                agent.setFullName(request.getFullName());
                agent.setPhoneNumber(request.getPhoneNumber());
                agent.setRole(User.UserRole.DELIVERY_AGENT);
                agent.setActive(true);
                user = deliveryAgentRepository.save(agent);
                break;

            default:
                throw new BusinessException("Invalid user role");
        }

        MDC.put(LoggingConstants.USER_ID, String.valueOf(user.getId()));
        MDC.put(LoggingConstants.USER_EMAIL, user.getEmail());

        AuthResponse response = issueTokenPair(user);
        securityEventLogger.logRegistration(user.getId(), user.getEmail(), user.getRole().name());
        return response;
    }

    @Override
    public AuthResponse login(LoginRequest request) {
        log.info("Login attempt | Email: {}", request.getEmail());

        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
            );

            User user = userRepository.findByEmail(request.getEmail())
                    .orElseThrow(() -> new BusinessException("User not found"));

            if (!user.getActive()) {
                securityEventLogger.logLoginFailure(request.getEmail(), "Account deactivated");
                throw new BusinessException("Account is deactivated");
            }

            MDC.put(LoggingConstants.USER_ID, String.valueOf(user.getId()));
            MDC.put(LoggingConstants.USER_EMAIL, user.getEmail());

            AuthResponse response = issueTokenPair(user);
            securityEventLogger.logLoginSuccess(user.getId(), user.getEmail(), user.getRole().name());
            return response;

        } catch (BadCredentialsException e) {
            securityEventLogger.logLoginFailure(request.getEmail(), "Invalid credentials");
            throw e;
        }
    }

    @Override
    public void verifyEmail(String email, String token) {
        if (token == null || token.isEmpty()) {
            throw new BusinessException("Token is required");
        }

        String tokenEmail = jwtTokenProvider.extractUsername(token);
        if (!email.equals(tokenEmail)) {
            throw new UnauthorizedException("Token does not match email");
        }
        if (!jwtTokenProvider.validateToken(token)) {
            throw new UnauthorizedException("Invalid verification token");
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException("User not found"));

        if (user.getEmailVerified()) {
            throw new BusinessException("Email already verified");
        }

        user.setEmailVerified(true);
        userRepository.save(user);
        log.info("Email verified | UserId: {} | Email: {}", user.getId(), email);
    }

    @Override
    public void forgotPassword(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException("User not found"));

        String resetToken = authTokenService.createPasswordResetToken(email, RESET_TOKEN_TTL);
        notificationService.sendPasswordReset(email, resetToken);
        log.info("Password reset requested | UserId: {} | Email: {}", user.getId(), email);
    }

    @Override
    public void resetPassword(String token, String newPassword) {
        if (token == null || token.isEmpty()) {
            throw new BusinessException("Token is required");
        }

        String email = authTokenService.validatePasswordResetToken(token);
        if (email == null) {
            throw new BusinessException("Invalid or expired reset token");
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException("User not found"));

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        authTokenService.consumePasswordResetToken(token);
        authTokenService.revokeAllRefreshTokens(user.getId());

        securityEventLogger.logPasswordChange(user.getId(), email);
    }

    @Override
    public AuthResponse refreshToken(String token) {
        if (token == null || token.isEmpty()) {
            throw new BusinessException("Token is required");
        }
        if (!jwtTokenProvider.validateToken(token) || !jwtTokenProvider.isRefreshToken(token)) {
            throw new UnauthorizedException("Invalid refresh token");
        }

        String email = jwtTokenProvider.extractUsername(token);
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException("User not found"));

        if (!user.getActive()) {
            throw new BusinessException("Account is deactivated");
        }
        if (!authTokenService.isRefreshTokenValid(user.getId(), token)) {
            throw new UnauthorizedException("Refresh token revoked or expired");
        }

        authTokenService.revokeRefreshToken(user.getId(), token);
        return issueTokenPair(user);
    }

    @Override
    public void changePassword(String token, String oldPassword, String newPassword) {
        if (token == null || token.isEmpty()) {
            throw new BusinessException("Token is required");
        }

        String email = jwtTokenProvider.extractUsername(token);
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException("User not found"));

        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new BusinessException("Current password is incorrect");
        }

        if (newPassword.length() < 6) {
            throw new BusinessException("New password must be at least 6 characters");
        }

        if (oldPassword.equals(newPassword)) {
            throw new BusinessException("New password must be different");
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        authTokenService.revokeAllRefreshTokens(user.getId());
        securityEventLogger.logPasswordChange(user.getId(), email);
    }

    @Override
    public void logout(String token) {
        try {
            if (token != null && !token.isEmpty()) {
                if (jwtTokenProvider.validateToken(token)) {
                    String email = jwtTokenProvider.extractUsername(token);
                    userRepository.findByEmail(email).ifPresent(user -> {
                        if (jwtTokenProvider.isRefreshToken(token)) {
                            authTokenService.revokeRefreshToken(user.getId(), token);
                        } else {
                            authTokenService.blacklistAccessToken(
                                    token, jwtTokenProvider.getRemainingValidityMs(token));
                        }
                    });
                    log.info("User logged out | Email: {}", email);
                }
            } else {
                log.info("User logged out | No token provided");
            }
        } catch (Exception e) {
            log.warn("Logout - token issue: {}", e.getMessage());
        }
    }

    private AuthResponse issueTokenPair(User user) {
        UserDetails userDetails = org.springframework.security.core.userdetails.User.builder()
                .username(user.getEmail())
                .password(user.getPassword())
                .authorities("ROLE_" + user.getRole().name())
                .build();

        String accessToken = jwtTokenProvider.generateAccessToken(userDetails);
        String refreshToken = jwtTokenProvider.generateRefreshToken(userDetails);
        authTokenService.storeRefreshToken(
                user.getId(),
                refreshToken,
                jwtTokenProvider.getRemainingValidityMs(refreshToken));

        return AuthResponse.builder()
                .token(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .userId(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .role(user.getRole().name())
                .build();
    }
}
