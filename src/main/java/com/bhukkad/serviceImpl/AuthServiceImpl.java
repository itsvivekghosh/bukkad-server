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
import com.bhukkad.security.JwtTokenProvider;
import com.bhukkad.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);

    private final UserRepository userRepository;
    private final CustomerRepository customerRepository;
    private final RestaurantOwnerRepository restaurantOwnerRepository;
    private final DeliveryAgentRepository deliveryAgentRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final AuthenticationManager authenticationManager;
    private final SecurityEventLogger securityEventLogger;

    @Override
    @Transactional
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

        String token = generateToken(user);
        securityEventLogger.logRegistration(user.getId(), user.getEmail(), user.getRole().name());

        return buildAuthResponse(user, token);
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

            String token = generateToken(user);
            securityEventLogger.logLoginSuccess(user.getId(), user.getEmail(), user.getRole().name());

            return buildAuthResponse(user, token);

        } catch (BadCredentialsException e) {
            securityEventLogger.logLoginFailure(request.getEmail(), "Invalid credentials");
            throw e;
        }
    }

    @Override
    @Transactional
    public void verifyEmail(String email, String token) {
        User user;

        if (token != null && !token.isEmpty()) {
            try {
                String tokenEmail = jwtTokenProvider.extractUsername(token);
                if (!email.equals(tokenEmail)) {
                    throw new UnauthorizedException("Token does not match email");
                }
            } catch (Exception e) {
                log.warn("Token validation failed for email verification: {}", e.getMessage());
            }
        }

        user = userRepository.findByEmail(email)
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
        log.info("Password reset requested | UserId: {} | Email: {}", user.getId(), email);
    }

    @Override
    @Transactional
    public void resetPassword(String token, String newPassword) {
        if (token == null || token.isEmpty()) {
            throw new BusinessException("Token is required");
        }

        String email = jwtTokenProvider.extractUsername(token);
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException("User not found"));

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        securityEventLogger.logPasswordChange(user.getId(), email);
    }

    @Override
    public AuthResponse refreshToken(String token) {
        if (token == null || token.isEmpty()) {
            throw new BusinessException("Token is required");
        }

        String email = jwtTokenProvider.extractUsername(token);
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException("User not found"));

        if (!user.getActive()) {
            throw new BusinessException("Account is deactivated");
        }

        String newToken = generateToken(user);
        return buildAuthResponse(user, newToken);
    }

    @Override
    @Transactional
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
        securityEventLogger.logPasswordChange(user.getId(), email);
    }

    @Override
    public void logout(String token) {
        try {
            if (token != null && !token.isEmpty()) {
                String email = jwtTokenProvider.extractUsername(token);
                log.info("User logged out | Email: {}", email);
            } else {
                log.info("User logged out | No token provided");
            }
        } catch (Exception e) {
            // Logout should never fail
            log.warn("Logout - token issue: {}", e.getMessage());
        }
        // Always successful
    }

    // ==================== HELPERS ====================

    private String generateToken(User user) {
        return jwtTokenProvider.generateToken(
                org.springframework.security.core.userdetails.User.builder()
                        .username(user.getEmail())
                        .password(user.getPassword())
                        .authorities("ROLE_" + user.getRole().name())
                        .build()
        );
    }

    private AuthResponse buildAuthResponse(User user, String token) {
        return AuthResponse.builder()
                .token(token)
                .tokenType("Bearer")
                .userId(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .role(user.getRole().name())
                .build();
    }
}