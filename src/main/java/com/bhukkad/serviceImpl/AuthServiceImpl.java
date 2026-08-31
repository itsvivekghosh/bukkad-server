package com.bhukkad.serviceImpl;

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
import com.bhukkad.security.AccountFields;
import com.bhukkad.security.AccountLookupService;
import com.bhukkad.security.AuthResultCache;
import com.bhukkad.security.AuthTokenService;
import com.bhukkad.security.JwtTokenProvider;
import com.bhukkad.referral.AffiliateService;
import com.bhukkad.referral.ReferralService;
import com.bhukkad.service.AuthService;
import com.bhukkad.service.NotificationService;
import com.bhukkad.service.PhoneVerificationService;
import com.bhukkad.util.Constants;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);
    private static final Duration RESET_TOKEN_TTL = Duration.ofMinutes(30);

    private final UserRepository userRepository;
    private final AccountLookupService accountLookupService;
    private final CustomerRepository customerRepository;
    private final RestaurantOwnerRepository restaurantOwnerRepository;
    private final DeliveryAgentRepository deliveryAgentRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final AuthenticationManager authenticationManager;
    private final SecurityEventLogger securityEventLogger;
    private final AuthTokenService authTokenService;
    private final NotificationService notificationService;
    private final PhoneVerificationService phoneVerificationService;
    private final ReferralService referralService;
    private final AffiliateService affiliateService;
    private final Executor lowPriorityTaskExecutor;
    private final AuthResultCache authResultCache;

    public AuthServiceImpl(UserRepository userRepository,
                           AccountLookupService accountLookupService,
                           CustomerRepository customerRepository,
                           RestaurantOwnerRepository restaurantOwnerRepository,
                           DeliveryAgentRepository deliveryAgentRepository,
                           PasswordEncoder passwordEncoder,
                           JwtTokenProvider jwtTokenProvider,
                           AuthenticationManager authenticationManager,
                           SecurityEventLogger securityEventLogger,
                           AuthTokenService authTokenService,
                           NotificationService notificationService,
                           PhoneVerificationService phoneVerificationService,
                           ReferralService referralService,
                           AffiliateService affiliateService,
                           @Qualifier("lowPriorityTaskExecutor") Executor lowPriorityTaskExecutor,
                           AuthResultCache authResultCache) {
        this.userRepository = userRepository;
        this.accountLookupService = accountLookupService;
        this.customerRepository = customerRepository;
        this.restaurantOwnerRepository = restaurantOwnerRepository;
        this.deliveryAgentRepository = deliveryAgentRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.authenticationManager = authenticationManager;
        this.securityEventLogger = securityEventLogger;
        this.authTokenService = authTokenService;
        this.notificationService = notificationService;
        this.phoneVerificationService = phoneVerificationService;
        this.referralService = referralService;
        this.affiliateService = affiliateService;
        this.lowPriorityTaskExecutor = lowPriorityTaskExecutor;
        this.authResultCache = authResultCache;
    }

    /**
     * Registers a new user account.
     *
     * <p>The whole registration is transactional: if any step fails (e.g. an
     * invalid referral code is rejected), the customer row saved earlier in
     * this method is rolled back instead of leaving an orphaned,
     * half-registered account. It also means the customer entity stays managed
     * so referral-code mutations set by {@link ReferralService} are flushed in
     * the same commit — no second explicit save is needed.</p>
     */
    @Override
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        log.info("Registration attempt | Email: {} | Role: {}", request.getEmail(), request.getRole());

        if (accountLookupService.existsAnywhereByEmail(request.getEmail())) {
            throw new BusinessException("Email already exists");
        }

        if (request.getPhoneNumber() != null &&
                accountLookupService.existsAnywhereByPhoneNumber(request.getPhoneNumber())) {
            throw new BusinessException("Phone number already exists");
        }

        User user;

        User.UserRole role = request.getRole();
        if (role == null) {
            role = User.UserRole.CUSTOMER;
        }

        switch (role) {
            case CUSTOMER:
                Customer customer = new Customer();
                customer.setEmail(request.getEmail());
                customer.setPassword(passwordEncoder.encode(request.getPassword()));
                customer.setFullName(request.getFullName());
                customer.setPhoneNumber(request.getPhoneNumber());
                customer.setRole(User.UserRole.CUSTOMER);
                customer.setActive(true);
                customer = customerRepository.save(customer);
                referralService.initializeNewCustomer(customer, request.getReferralCode());
                if (request.getAffiliateCode() != null && !request.getAffiliateCode().isBlank()) {
                    affiliateService.recordSignup(request.getAffiliateCode(), customer.getId());
                }
                user = customer;
                break;

            case RESTAURANT_OWNER:
                RestaurantOwner owner = new RestaurantOwner();
                owner.setEmail(request.getEmail());
                owner.setPassword(passwordEncoder.encode(request.getPassword()));
                owner.setFullName(request.getFullName());
                owner.setPhoneNumber(request.getPhoneNumber());
                owner.setRole(User.UserRole.RESTAURANT_OWNER);
                owner.setActive(true);
                // Not verified by default — admin must call verifyRestaurantOwner
                // before the account can perform privileged operations. This
                // prevents instant privileged-role escalation via self-registration.
                owner.setVerified(false);
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
        MDC.put(LoggingConstants.USER_EMAIL, AccountFields.email(user));

        AuthResponse response = issueTokenPair(user);
        securityEventLogger.logRegistration(user.getId(), AccountFields.email(user), user.getRole().name());
        return response;
    }

    @Override
    public AuthResponse login(LoginRequest request) {
        log.info("Login attempt | Email: {}", request.getEmail());

        try {
            // Check auth cache first to skip expensive password hashing on
            // repeat logins within the TTL window.
            Authentication authentication = null;
            String email = request.getEmail();
            String password = request.getPassword();

            if (authResultCache != null) {
                try {
                    authentication = authResultCache.authenticate(email, password);
                } catch (org.springframework.security.core.AuthenticationException e) {
                    // Cache miss or invalid credentials - fall through to full auth
                    log.debug("AUTH_CACHE_MISS | full auth required | email={}", email);
                }
            }

            if (authentication == null) {
                authentication = authenticationManager.authenticate(
                        new UsernamePasswordAuthenticationToken(email, password)
                );
            }

            email = authentication.getName();
            User user = accountLookupService.byEmail(email)
                    .orElseThrow(() -> new BusinessException("User not found"));

            if (!user.getActive()) {
                securityEventLogger.logLoginFailure(request.getEmail(), "Account deactivated");
                throw new BusinessException("Account is deactivated");
            }

            // Rehash the stored password to Argon2id when it was encoded with a
            // legacy scheme (plain BCrypt before the upgrade). The raw password is
            // known here because authenticationManager already verified it above;
            // this becomes a no-op once the stored hash carries the {argon2} prefix.
            String currentPassword = AccountFields.password(user);
            if (currentPassword != null
                    && (passwordEncoder.upgradeEncoding(currentPassword)
                        || !currentPassword.startsWith("{argon2}"))) {
                AccountFields.setPassword(user, passwordEncoder.encode(request.getPassword()));
                // Async save to avoid blocking the login response on a write that
                // does not affect token issuance.
                CompletableFuture.runAsync(() -> userRepository.save(user), lowPriorityTaskExecutor)
                        .exceptionally(e -> {
                            log.error("Password rehash save failed for userId={}", user.getId(), e);
                            return null;
                        });
            }

            MDC.put(LoggingConstants.USER_ID, String.valueOf(user.getId()));
            MDC.put(LoggingConstants.USER_EMAIL, AccountFields.email(user));

            // If the account belongs to a privileged role and has TOTP MFA
            // enabled, require a second-factor challenge before issuing tokens.
            if (Boolean.TRUE.equals(user.getTotpEnabled()) && isMfaEligibleRole(user.getRole())) {
                String mfaToken = jwtTokenProvider.generateMfaToken(user.getId(), AccountFields.email(user));
                log.info("MFA challenge issued | userId={} | role={}", user.getId(), user.getRole());
                securityEventLogger.logLoginSuccess(user.getId(), AccountFields.email(user), user.getRole().name());
                return AuthResponse.builder()
                        .mfaRequired(true)
                        .mfaToken(mfaToken)
                        .userId(user.getId())
                        .email(AccountFields.email(user))
                        .fullName(AccountFields.fullName(user))
                        .role(user.getRole().name())
                        .build();
            }

            AuthResponse response = issueTokenPair(user);
            securityEventLogger.logLoginSuccess(user.getId(), AccountFields.email(user), user.getRole().name());
            return response;

        } catch (BadCredentialsException e) {
            securityEventLogger.logLoginFailure(request.getEmail(), "Invalid credentials");
            throw e;
        }
    }

    @Override
    public AuthResponse verifyMfaLogin(String mfaToken, String totpCode) {
        if (!jwtTokenProvider.validateMfaToken(mfaToken)) {
            throw new UnauthorizedException("MFA token expired or invalid");
        }
        // extractUserId and extractUsername are safe to call after validation.
        Long userId = jwtTokenProvider.extractUserId(mfaToken);
        String email = jwtTokenProvider.extractUsername(mfaToken);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found"));

        if (!com.bhukkad.util.TOTPGenerator.verify(AccountFields.totpSecret(user), totpCode, 1)) {
            securityEventLogger.logLoginFailure(email, "Invalid MFA code");
            throw new BusinessException("Invalid MFA code");
        }

        log.info("MFA verification succeeded | userId={}", userId);
        return issueTokenPair(user);
    }

    // ------------------------------------------------------------------
    // Phone-first registration
    // ------------------------------------------------------------------

    /**
     * Creates an account with only a phone number. A placeholder email is
     * derived from the phone to satisfy the unique-email constraint, and an
     * OTP is sent via SMS or WhatsApp for phone verification.
     *
     * <p>The OTP is sent <strong>before</strong> the user row is persisted. If
     * the notification fails or Redis is unavailable, a {@link BusinessException}
     * is thrown and no account is created — the caller must retry registration.</p>
     */
    @Override
    @Transactional
    public PhoneRegisterResponse registerPhone(PhoneRegisterRequest request) {
        log.info("Phone-first registration attempt | phone={}", maskPhone(request.getPhoneNumber()));

        if (accountLookupService.existsAnywhereByPhoneNumber(request.getPhoneNumber())) {
            throw new BusinessException("Phone number already registered");
        }

        if (request.getRole() != null && request.getRole() != User.UserRole.CUSTOMER) {
            throw new BusinessException("Phone-first registration only supports role CUSTOMER. " +
                    "Use the email-based register endpoint for other roles.");
        }

        // Send OTP first — if delivery fails, throw to abort the transaction
        // so no half-registered account is persisted to the database.
        String otpChannel = request.getOtpChannel() != null ? request.getOtpChannel() : "sms";
        phoneVerificationService.sendOtp(request.getPhoneNumber(), otpChannel);

        User user = createPhoneCustomer(request.getPhoneNumber(), request.getPassword());

        MDC.put(LoggingConstants.USER_ID, String.valueOf(user.getId()));

        return PhoneRegisterResponse.builder()
                .phoneNumber(AccountFields.phoneNumber(user))
                .message("OTP sent. Please verify your phone number to continue.")
                .otpExpiryMinutes(Constants.OTP_EXPIRY_MINUTES)
                .build();
    }

    /**
     * Creates a CUSTOMER account backed only by a phone number: a placeholder
     * email satisfies the unique-email constraint, no password is set until the
     * profile-completion step. Shared by phone-first registration and the
     * unified phone sign-in (create-or-login).
     */
    private User createPhoneCustomer(String phoneNumber, String rawPassword) {
        String placeholderEmail = "phone_" + phoneNumber + "@temp.bhukkad.local";
        Customer customer = new Customer();
        customer.setPhoneNumber(phoneNumber);
        customer.setEmail(placeholderEmail);
        customer.setFullName(null);
        customer.setPassword(rawPassword != null
                ? passwordEncoder.encode(rawPassword)
                : null);
        customer.setRole(User.UserRole.CUSTOMER);
        customer.setActive(true);
        customer.setPhoneVerified(false);
        customer.setProfileCompleted(rawPassword != null);
        customer = customerRepository.save(customer);
        referralService.initializeNewCustomer(customer, null);
        return customer;
    }

    /**
     * Validates the OTP sent during {@link #registerPhone} and issues the
     * JWT token pair on success. The OTP is deleted from Redis in the same
     * transaction as the user update so a successful verification never leaves
     * a stale, reusable OTP behind.
     */
    @Override
    @Transactional
    public AuthResponse verifyPhone(OtpVerifyRequest request) {
        User user = accountLookupService.byPhoneNumber(request.getPhoneNumber())
                .orElseThrow(() -> new BusinessException("No registration found for this phone number"));

        phoneVerificationService.verifyOtp(request.getPhoneNumber(), request.getCode());

        user.setPhoneVerified(true);
        user.setPhoneVerifiedAt(LocalDateTime.now());
        userRepository.save(user);

        log.info("Phone verified and tokens issued | userId={}", user.getId());
        MDC.put(LoggingConstants.USER_ID, String.valueOf(user.getId()));
        return issueTokenPair(user);
    }

    /**
     * Resends a fresh OTP, invalidating the previous one.
     */
    @Override
    public void resendPhoneOtp(String phoneNumber, String channel) {
        User user = accountLookupService.byPhoneNumber(phoneNumber)
                .orElseThrow(() -> new BusinessException("No registration found for this phone number"));
        phoneVerificationService.resendOtp(phoneNumber, channel);
    }

    // ------------------------------------------------------------------
    // Unified phone sign-in (create-or-login)
    // ------------------------------------------------------------------

    /**
     * Sends an OTP (SMS or WhatsApp) for phone sign-in. Works whether or not an
     * account exists for the number — verification decides later whether this is
     * a login or a first-time sign-up. Returns {@code isNewUser} so the client
     * can tailor its message.
     */
    @Override
    @Transactional
    public PhoneSendOtpResponse sendPhoneLoginOtp(PhoneSendOtpRequest request) {
        String phone = request.getPhoneNumber();
        String channel = request.getChannel() != null && !request.getChannel().isBlank()
                ? request.getChannel() : "sms";
        boolean isNewUser = !accountLookupService.existsAnywhereByPhoneNumber(phone);

        phoneVerificationService.sendOtp(phone, channel);

        log.info("Phone sign-in OTP sent | phone={} | channel={} | isNewUser={}",
                maskPhone(phone), channel, isNewUser);

        return PhoneSendOtpResponse.builder()
                .phoneNumber(phone)
                .message("OTP sent")
                .otpExpiryMinutes(Constants.OTP_EXPIRY_MINUTES)
                .isNewUser(isNewUser)
                .build();
    }

    /**
     * Verifies the OTP for phone sign-in and issues tokens.
     *
     * <p><strong>Create-or-login:</strong> if no account exists for the phone,
     * a new CUSTOMER is created first; otherwise the existing user is signed in.
     * The response's {@code isNewUser} flag tells the client whether to prompt
     * for profile completion.</p>
     */
    @Override
    @Transactional
    public AuthResponse verifyPhoneLogin(OtpVerifyRequest request) {
        String phone = request.getPhoneNumber();
        phoneVerificationService.verifyOtp(phone, request.getCode());

        boolean isNewUser = !accountLookupService.existsAnywhereByPhoneNumber(phone);
        User user = accountLookupService.byPhoneNumber(phone)
                .orElseGet(() -> createPhoneCustomer(phone, null));

        if (!user.getPhoneVerified()) {
            user.setPhoneVerified(true);
            user.setPhoneVerifiedAt(LocalDateTime.now());
            userRepository.save(user);
        }

        log.info("Phone sign-in completed | userId={} | isNewUser={}", user.getId(), isNewUser);
        MDC.put(LoggingConstants.USER_ID, String.valueOf(user.getId()));

        AuthResponse response = issueTokenPair(user);
        response.setNewUser(isNewUser);
        return response;
    }

    /**
     * Completes a phone-first customer's profile by adding email, full name,
     * and an optional password. Marks the account as profile-completed so
     * downstream features (email-based login, email verification) are unlocked.
     *
     * <p>Email verification is triggered separately: after this call the user
     * can re-authenticate with their new email and call
     * {@code POST /auth/verify-email} to verify it.</p>
     */
    @Override
    @Transactional
    public void completeProfile(Long userId, CompleteProfileRequest req) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found"));

        if (req.getEmail() != null && !req.getEmail().isBlank()) {
            if (accountLookupService.existsAnywhereByEmail(req.getEmail())) {
                User existing = accountLookupService.byEmail(req.getEmail())
                        .orElseThrow(() -> new BusinessException("Email already in use"));
                if (!existing.getId().equals(userId)) {
                    throw new BusinessException("Email already in use by another account");
                }
            }
            AccountFields.setEmail(user,req.getEmail());
        }

        if (req.getFullName() != null && !req.getFullName().isBlank()) {
            AccountFields.setFullName(user,req.getFullName());
        }

        if (req.getPassword() != null && !req.getPassword().isBlank()) {
            AccountFields.setPassword(user,passwordEncoder.encode(req.getPassword()));
        }

        user.setProfileCompleted(true);
        userRepository.save(user);

        log.info("Profile completed | userId={}", user.getId());
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) return "****";
        return "****" + phone.substring(phone.length() - 4);
    }

    private boolean isMfaEligibleRole(User.UserRole role) {
        return role == User.UserRole.ADMIN || role == User.UserRole.RESTAURANT_OWNER;
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

        User user = accountLookupService.byEmail(email)
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
        User user = accountLookupService.byEmail(email)
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

        User user = accountLookupService.byEmail(email)
                .orElseThrow(() -> new BusinessException("User not found"));

        AccountFields.setPassword(user,passwordEncoder.encode(newPassword));
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
        User user = accountLookupService.byEmail(email)
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
        User user = accountLookupService.byEmail(email)
                .orElseThrow(() -> new BusinessException("User not found"));

        String currentPassword = AccountFields.password(user);
        if (currentPassword == null || !passwordEncoder.matches(oldPassword, currentPassword)) {
            throw new BusinessException("Current password is incorrect");
        }

        if (newPassword.length() < 6) {
            throw new BusinessException("New password must be at least 6 characters");
        }

        if (oldPassword.equals(newPassword)) {
            throw new BusinessException("New password must be different");
        }

        AccountFields.setPassword(user, passwordEncoder.encode(newPassword));
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
                    accountLookupService.byEmail(email).ifPresent(user -> {
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
        String loginId = AccountFields.email(user) != null ? AccountFields.email(user) : AccountFields.phoneNumber(user);
        String password = AccountFields.password(user) != null ? AccountFields.password(user) : "";
        UserDetails userDetails = org.springframework.security.core.userdetails.User.builder()
                .username(loginId)
                .password(password)
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
                .email(AccountFields.email(user))
                .phoneNumber(AccountFields.phoneNumber(user))
                .fullName(AccountFields.fullName(user))
                .role(user.getRole().name())
                .build();
    }
}
