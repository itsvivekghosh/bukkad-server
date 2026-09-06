package com.bhukkad.identity.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.DuplicateRequestException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.common.error.UnauthorizedException;
import com.bhukkad.identity.domain.Admin;
import com.bhukkad.identity.domain.AdminRepository;
import com.bhukkad.identity.domain.Customer;
import com.bhukkad.identity.domain.CustomerRepository;
import com.bhukkad.identity.domain.DeliveryAgent;
import com.bhukkad.identity.domain.DeliveryAgentRepository;
import com.bhukkad.identity.domain.RestaurantOwner;
import com.bhukkad.identity.domain.RestaurantOwnerRepository;
import com.bhukkad.identity.domain.User;
import com.bhukkad.identity.domain.UserRepository;
import com.bhukkad.identity.service.IdentityEventPublisher;
import com.bhukkad.identity.security.JwtService;
import com.bhukkad.identity.security.PasswordService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Identity service (P3): registration, login, token issue/refresh, email
 * verification and self-service password changes. Credentials live in
 * {@code customers}; role-specific state in the JOINED inheritance tables.
 */
@Service
@RequiredArgsConstructor
public class IdentityService {
    private static final Logger log = LoggerFactory.getLogger(IdentityService.class);
    private static final Duration RESET_TOKEN_TTL = Duration.ofMinutes(30);

    private final CustomerRepository customerRepository;
    private final PasswordService passwordService;
    private final JwtService jwtService;
    private final IdentityEventPublisher eventPublisher;
    private final UserRepository userRepository;
    private final RestaurantOwnerRepository restaurantOwnerRepository;
    private final DeliveryAgentRepository deliveryAgentRepository;
    private final AdminRepository adminRepository;

    @PersistenceContext
    private EntityManager em;

    @Transactional
    public Customer register(String email, String phoneNumber, String fullName, String rawPassword, String requestedRole) {
        String role = normalizeRole(requestedRole);
        if (userRepositoryExists(email)) {
            throw new DuplicateRequestException("Email already registered: " + email);
        }

        Customer customer = new Customer();
        customer.setEmail(email);
        customer.setPhoneNumber(phoneNumber);
        customer.setFullName(fullName);
        customer.setPasswordHash(passwordService.hash(rawPassword));
        customer.setReferralCode(generateReferralCode());
        Customer saved = customerRepository.saveAndFlush(customer);

        em.createNativeQuery("INSERT INTO users (id, role, active, email_verified, phone_verified, "
                         + "profile_completed, totp_enabled, created_at, updated_at) "
                         + "OVERRIDING SYSTEM VALUE "
                         + "VALUES (:id, :role, true, false, false, false, false, now(), now())")
                .setParameter("id", saved.getId())
                .setParameter("role", role)
                .executeUpdate();

        if ("RESTAURANT_OWNER".equals(role)) {
            RestaurantOwner owner = new RestaurantOwner();
            owner.setId(saved.getId());
            owner.setEmail(email);
            owner.setFullName(fullName);
            owner.setPhoneNumber(phoneNumber);
            owner.setRole(User.UserRole.RESTAURANT_OWNER);
            restaurantOwnerRepository.save(owner);
        } else if ("DELIVERY_AGENT".equals(role)) {
            DeliveryAgent agent = new DeliveryAgent();
            agent.setId(saved.getId());
            agent.setEmail(email);
            agent.setFullName(fullName);
            agent.setPhoneNumber(phoneNumber);
            agent.setRole(User.UserRole.DELIVERY_AGENT);
            deliveryAgentRepository.save(agent);
        }

        eventPublisher.customerRegistered(saved.getId(), email, fullName);
        return saved;
    }

    @Transactional(readOnly = true)
    public LoginResult login(String email, String rawPassword) {
        Optional<Customer> customerOpt = customerRepository.findByEmailAndIsActiveTrue(email);
        if (customerOpt.isPresent()) {
            Customer customer = customerOpt.get();
            if (!passwordService.matches(rawPassword, customer.getPasswordHash())) {
                throw new UnauthorizedException("Invalid email or password");
            }
            String scope = resolveScope(customer.getId());
            String token = jwtService.issue(customer.getId(), customer.getEmail(), scope);
            return new LoginResult(token, customer.getId(), customer.getFullName(), scope);
        }
        // Admin fallback: credentials live on the admins row (seeded via bootstrap).
        Optional<Admin> adminOpt = adminRepository.findByEmail(email);
        if (adminOpt.isPresent()) {
            Admin admin = adminOpt.get();
            boolean active = userRepository.findById(admin.getId())
                    .map(u -> Boolean.TRUE.equals(u.getActive()))
                    .orElse(false);
            if (!active || !passwordService.matches(rawPassword, admin.getPassword())) {
                throw new UnauthorizedException("Invalid email or password");
            }
            String token = jwtService.issue(admin.getId(), admin.getEmail(), "admin");
            return new LoginResult(token, admin.getId(), admin.getFullName(), "ADMIN");
        }
        throw new UnauthorizedException("Invalid email or password");
    }

    /**
     * Rotates a still-valid access token into a fresh one. Invalid/expired
     * tokens are rejected with a generic 401 (no user enumeration).
     */
    @Transactional(readOnly = true)
    public LoginResult refresh(String token) {
        JwtService.IntrospectionResult result = jwtService.introspect(token);
        if (!result.valid() || result.customerId() == null) {
            throw new UnauthorizedException("Invalid or expired token");
        }
        String scope = result.scope() != null ? result.scope() : resolveScope(result.customerId());
        String fullName = customerRepository.findById(result.customerId())
                .map(Customer::getFullName)
                .orElse("user");
        String freshToken = jwtService.issue(result.customerId(), emailOf(result.customerId()), scope);
        return new LoginResult(freshToken, result.customerId(), fullName, scope);
    }

    @Transactional
    public void verifyEmail(String email) {
        Customer customer = customerRepository.findByEmailAndIsActiveTrue(email)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + email));
        customer.setEmailVerified(true);
        customerRepository.save(customer);
        em.createNativeQuery("UPDATE users SET email_verified = true, updated_at = now() WHERE id = :id")
                .setParameter("id", customer.getId())
                .executeUpdate();
    }

    @Transactional
    public void changePassword(Long userId, String currentPassword, String newPassword) {
        Customer customer = customerRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + userId));
        if (!passwordService.matches(currentPassword, customer.getPasswordHash())) {
            throw new UnauthorizedException("Current password is incorrect");
        }
        if (currentPassword.equals(newPassword)) {
            throw new BusinessException("New password must differ from the current password");
        }
        customer.setPasswordHash(passwordService.hash(newPassword));
        customerRepository.save(customer);
        log.info("PASSWORD_CHANGED userId={}", userId);
    }

    /**
     * Forgot-password: responds indifferently. If the email maps to an active
     * account, a single-use hashed reset token valid for 30 minutes is stored;
     * delivery happens through the notification pipeline.
     */
    @Transactional
    public void forgotPassword(String email) {
        customerRepository.findByEmailAndIsActiveTrue(email).ifPresent(customer -> {
            String token = generateResetToken();
            em.createNativeQuery(
                    "INSERT INTO password_reset_tokens (user_id, token_hash, expires_at, used) "
                    + "VALUES (:userId, :tokenHash, :expiresAt, false) "
                    + "ON CONFLICT (user_id) DO UPDATE SET token_hash = :tokenHash, "
                    + "expires_at = :expiresAt, used = false")
                    .setParameter("userId", customer.getId())
                    .setParameter("tokenHash", sha256(token))
                    .setParameter("expiresAt", java.sql.Timestamp.from(Instant.now().plus(RESET_TOKEN_TTL)))
                    .executeUpdate();
            log.info("PASSWORD_RESET_ISSUED userId={}", customer.getId());
        });
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new BusinessException("Invalid or expired reset token");
        }
        String tokenHash = sha256(rawToken);
        int updated = em.createNativeQuery(
                "UPDATE customers c SET password_hash = :hash, updated_at = now() "
                + "FROM password_reset_tokens t "
                + "WHERE t.user_id = c.id AND t.token_hash = :tokenHash AND t.used = false "
                + "AND t.expires_at > now()")
                .setParameter("hash", passwordService.hash(newPassword))
                .setParameter("tokenHash", tokenHash)
                .executeUpdate();
        em.createNativeQuery("UPDATE password_reset_tokens SET used = true WHERE token_hash = :tokenHash")
                .setParameter("tokenHash", tokenHash)
                .executeUpdate();
        if (updated == 0) {
            throw new BusinessException("Invalid or expired reset token");
        }
        log.info("PASSWORD_RESET_COMPLETED");
    }

    /** Self-registration is limited to non-privileged roles. */
    private static String normalizeRole(String requestedRole) {
        if (requestedRole == null || requestedRole.isBlank()) {
            return "CUSTOMER";
        }
        String upper = requestedRole.trim().toUpperCase();
        if ("CUSTOMER".equals(upper) || "RESTAURANT_OWNER".equals(upper) || "DELIVERY_AGENT".equals(upper)) {
            return upper;
        }
        throw new BusinessException("Unsupported self-registration role: " + requestedRole);
    }

    private boolean userRepositoryExists(String email) {
        return customerRepository.findByEmailAndIsActiveTrue(email).isPresent();
    }

    private String resolveScope(Long userId) {
        return userRepository.findById(userId)
                .map(u -> u.getRole().name())
                .orElse("CUSTOMER");
    }

    private String emailOf(Long userId) {
        return customerRepository.findById(userId).map(Customer::getEmail).orElse(null);
    }

    private static String generateResetToken() {
        byte[] buf = new byte[32];
        new SecureRandom().nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String generateReferralCode() {
        byte[] buf = new byte[6];
        new SecureRandom().nextBytes(buf);
        return "BK" + HexFormat.of().formatHex(buf).toUpperCase();
    }

    public record LoginResult(String token, Long customerId, String fullName, String role) {
    }
}
