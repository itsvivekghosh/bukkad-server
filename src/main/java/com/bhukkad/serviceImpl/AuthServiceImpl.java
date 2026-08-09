package com.bhukkad.serviceImpl;

import com.bhukkad.dto.request.LoginRequest;
import com.bhukkad.dto.request.RegisterRequest;
import com.bhukkad.dto.response.AuthResponse;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.DeliveryAgent;
import com.bhukkad.entity.RestaurantOwner;
import com.bhukkad.entity.User;
import com.bhukkad.exception.BusinessException;
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
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

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
            log.warn("Registration failed - Email already exists: {}", request.getEmail());
            throw new BusinessException("Email already exists");
        }

        if (request.getPhoneNumber() != null &&
                userRepository.existsByPhoneNumber(request.getPhoneNumber())) {
            log.warn("Registration failed - Phone already exists: {}", request.getPhoneNumber());
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

        // Set MDC context after registration
        MDC.put(LoggingConstants.USER_ID, String.valueOf(user.getId()));
        MDC.put(LoggingConstants.USER_EMAIL, user.getEmail());
        MDC.put(LoggingConstants.USER_ROLE, user.getRole().name());

        // Generate JWT token
        String token = jwtTokenProvider.generateToken(
                org.springframework.security.core.userdetails.User.builder()
                        .username(user.getEmail())
                        .password(user.getPassword())
                        .authorities("ROLE_" + user.getRole().name())
                        .build()
        );

        // Log security event
        securityEventLogger.logRegistration(user.getId(), user.getEmail(), user.getRole().name());

        log.info("Registration successful | UserId: {} | Email: {} | Role: {}",
                user.getId(), user.getEmail(), user.getRole());

        return AuthResponse.builder()
                .token(token)
                .userId(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .role(user.getRole().name())
                .build();
    }

    @Override
    public AuthResponse login(LoginRequest request) {
        log.info("Login attempt | Email: {}", request.getEmail());

        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
            );

            User user = userRepository.findByEmail(request.getEmail())
                    .orElseThrow(() -> new BusinessException("User not found"));

            if (!user.getActive()) {
                securityEventLogger.logLoginFailure(request.getEmail(), "Account deactivated");
                throw new BusinessException("Account is deactivated");
            }

            // Set MDC context after login
            MDC.put(LoggingConstants.USER_ID, String.valueOf(user.getId()));
            MDC.put(LoggingConstants.USER_EMAIL, user.getEmail());
            MDC.put(LoggingConstants.USER_ROLE, user.getRole().name());
            MDC.put(LoggingConstants.TIMESTAMP, Instant.now().toString());

            String token = jwtTokenProvider.generateToken(
                    org.springframework.security.core.userdetails.User.builder()
                            .username(user.getEmail())
                            .password(user.getPassword())
                            .authorities("ROLE_" + user.getRole().name())
                            .build()
            );

            // Log success
            securityEventLogger.logLoginSuccess(user.getId(), user.getEmail(), user.getRole().name());

            log.info("Login successful | UserId: {} | Email: {} | Role: {}",
                    user.getId(), user.getEmail(), user.getRole());

            return AuthResponse.builder()
                    .token(token)
                    .userId(user.getId())
                    .email(user.getEmail())
                    .fullName(user.getFullName())
                    .role(user.getRole().name())
                    .build();

        } catch (BadCredentialsException e) {
            securityEventLogger.logLoginFailure(request.getEmail(), "Invalid credentials");
            log.warn("Login failed | Email: {} | Reason: Bad credentials", request.getEmail());
            throw e;
        }
    }

    @Override
    public void verifyEmail(String email, String token) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException("User not found"));
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
    public void resetPassword(String token, String newPassword) {
        log.info("Password reset executed");
    }
}