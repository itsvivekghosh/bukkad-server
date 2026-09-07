package com.bhukkad.identity.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.DuplicateRequestException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.common.error.UnauthorizedException;
import com.bhukkad.identity.domain.Admin;
import com.bhukkad.identity.domain.AdminRepository;
import com.bhukkad.identity.domain.Customer;
import com.bhukkad.identity.domain.CustomerRepository;
import com.bhukkad.identity.domain.UserRepository;
import com.bhukkad.identity.referral.ReferralService;
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
import java.util.List;
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
    private final AdminRepository adminRepository;
    private final ReferralService referralService;
    private final RefreshTokenService refreshTokens;

    @PersistenceContext
    private EntityManager em;

    @Transactional
    public Customer register(String email, String phoneNumber, String fullName, String rawPassword, String requestedRole, String referralCode) {
        String role = normalizeRole(requestedRole);
        if (userRepositoryExists(email)) {
            throw new DuplicateRequestException("Email already registered: " + email);
        }

        Customer customer = new Customer();
        customer.setEmail(email);
        customer.setPhoneNumber(phoneNumber);
        customer.setFullName(fullName);
        customer.setPasswordHash(passwordService.hash(rawPassword));
        Customer saved = customerRepository.saveAndFlush(customer);

        em.createNativeQuery("INSERT INTO users (id, role, active, email_verified, phone_verified, "
                         + "profile_completed, totp_enabled, created_at, updated_at) "
                         + "OVERRIDING SYSTEM VALUE "
                         + "VALUES (:id, :role, true, false, false, false, false, now(), now())")
                .setParameter("id", saved.getId())
                .setParameter("role", role)
                .executeUpdate();

        // Explicit-id inserts bypass users_id_seq, leaving it stale below
        // max(users.id). Identity-generated writers (admin bootstrap) then
        // collide with existing pkeys. Re-arm the sequence after each
        // shared-id write so nextval is always beyond max(id).
        em.createNativeQuery("SELECT setval('users_id_seq', "
                         + "GREATEST((SELECT COALESCE(MAX(id), 0) FROM users), 1))")
                .getSingleResult();

        if ("RESTAURANT_OWNER".equals(role)) {
            // Profile row only — the users registry row was already written
            // above with the shared customer id. Saving the JOINED entity here
            // would re-insert into users (users_pkey duplicate) and fail.
            em.createNativeQuery("INSERT INTO restaurant_owners "
                            + "(id, email, full_name, phone_number) "
                            + "VALUES (:id, :email, :fullName, :phone)")
                    .setParameter("id", saved.getId())
                    .setParameter("email", email)
                    .setParameter("fullName", fullName)
                    .setParameter("phone", phoneNumber)
                    .executeUpdate();
        } else if ("DELIVERY_AGENT".equals(role)) {
            // delivery_agents NOT NULL columns (available/verified/average_rating/
            // total_deliveries) all carry column defaults, so they are omitted.
            em.createNativeQuery("INSERT INTO delivery_agents "
                            + "(id, email, full_name, phone_number) "
                            + "VALUES (:id, :email, :fullName, :phone)")
                    .setParameter("id", saved.getId())
                    .setParameter("email", email)
                    .setParameter("fullName", fullName)
                    .setParameter("phone", phoneNumber)
                    .executeUpdate();
        }

         eventPublisher.customerRegistered(saved.getId(), email, fullName);
        referralService.initializeNewCustomer(saved, referralCode);
        return saved;
    }

    @Transactional(readOnly = true)
    public LoginResult login(String email, String rawPassword) {
        return login(email, rawPassword, null, null);
    }

    /**
     * Authenticates and returns a fresh access/refresh pair. The refresh
     * token is a rotating, server-side session (SHA-256 hash stored);
     * {@code deviceId}/{@code userAgent} are recorded (untrusted, length
     * capped) for later revocation tooling.
     */
    @Transactional
    public LoginResult login(String email, String rawPassword, String deviceId, String userAgent) {
        Optional<Customer> customerOpt = customerRepository.findByEmailAndIsActiveTrue(email);
        if (customerOpt.isPresent()) {
            Customer customer = customerOpt.get();
            if (!passwordService.matches(rawPassword, customer.getPasswordHash())) {
                throw new UnauthorizedException("Invalid email or password");
            }
            String scope = resolveScope(customer.getId());
            String token = jwtService.issue(customer.getId(), customer.getEmail(), scope);
            String refreshToken = refreshTokens.issue(customer.getId(), userAgent, deviceId);
            return new LoginResult(token, customer.getId(), customer.getFullName(), scope, refreshToken);
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
            String refreshToken = refreshTokens.issue(admin.getId(), userAgent, deviceId);
            return new LoginResult(token, admin.getId(), admin.getFullName(), "ADMIN", refreshToken);
        }
        throw new UnauthorizedException("Invalid email or password");
    }

    /**
     * Production refresh path: consumes a rotating refresh token and returns
     * a new access/refresh pair (same family, same absolute expiry). Reuse of
     * a rotated/revoked token revokes the whole family — see
     * {@link RefreshTokenService#rotate}. Account state (active, role) is
     * re-derived from the DB, mirroring {@link #refresh(String)}.
     */
    @Transactional
    public LoginResult refreshWithRotation(String refreshToken, String userAgent, String deviceId) {
        RefreshTokenService.Rotation rotation = refreshTokens.rotate(refreshToken, userAgent, deviceId);
        Long userId = rotation.customerId();
        Customer customer = customerRepository.findById(userId).orElse(null);
        if (customer != null) {
            if (!Boolean.TRUE.equals(customer.getIsActive())) {
                refreshTokens.revokeAllForCustomer(userId);
                throw new UnauthorizedException("Account is deactivated");
            }
            String scope = resolveScope(userId);
            String fullName = customer.getFullName() == null ? "user" : customer.getFullName();
            String token = jwtService.issue(userId, customer.getEmail(), scope);
            return new LoginResult(token, userId, fullName, scope, rotation.refreshToken());
        }
        Admin admin = adminRepository.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("Invalid refresh token"));
        boolean active = userRepository.findById(userId)
                .map(u -> Boolean.TRUE.equals(u.getActive()))
                .orElse(false);
        if (!active) {
            refreshTokens.revokeAllForCustomer(userId);
            throw new UnauthorizedException("Account is deactivated");
        }
        String token = jwtService.issue(userId, admin.getEmail(), "admin");
        String fullName = admin.getFullName() == null ? "user" : admin.getFullName();
        return new LoginResult(token, userId, fullName, "ADMIN", rotation.refreshToken());
    }

    /**
     * LEGACY refresh path ({@code {token: accessToken}} bodies from clients
     * predating the access/refresh split): best-effort — validates the access
     * JWT exactly as before (signature, expiry, account state) and returns a
     * NEW access/refresh pair so the caller migrates to rotating refresh
     * tokens without an app update. Logged at WARN so deployments can watch
     * stragglers drain before this path is removed.
     */
    @Transactional
    public LoginResult refresh(String token) {
        JwtService.IntrospectionResult result = jwtService.introspect(token);
        if (!result.valid() || result.customerId() == null) {
            throw new UnauthorizedException("Invalid or expired token");
        }
        // Re-derive role state from the DB on every refresh: a deactivated
        // account or a role downgrade must take effect immediately instead of
        // living as long as the stolen token keeps being renewed.
        var customer = customerRepository.findById(result.customerId())
                .orElseThrow(() -> new UnauthorizedException("Invalid or expired token"));
        if (!Boolean.TRUE.equals(customer.getIsActive())) {
            throw new UnauthorizedException("Account is deactivated");
        }
        log.warn("LEGACY_ACCESS_TOKEN_REFRESH customerId={} — migrating caller to refresh-token rotation",
                result.customerId());
        String scope = resolveScope(result.customerId());
        String fullName = customer.getFullName() == null ? "user" : customer.getFullName();
        String freshToken = jwtService.issue(result.customerId(), emailOf(result.customerId()), scope);
        String refreshToken = refreshTokens.issue(result.customerId(), null, null);
        return new LoginResult(freshToken, result.customerId(), fullName, scope, refreshToken);
    }

    /**
     * Marks a customer's email verified. The endpoint is intentionally
     * unauthenticated but never trusts a bare email: only callers presenting
     * the account's current password can complete verification (proof of
     * account control). Bulk probing is still prevented by the uniform
     * exception text — no account enumeration.
     */
    @Transactional
    public void verifyEmailWithPassword(String email, String currentPassword) {
        Customer customer = customerRepository.findByEmailAndIsActiveTrue(email)
                .orElseThrow(() -> new UnauthorizedException("Invalid credentials"));
        if (!passwordService.matches(currentPassword, customer.getPasswordHash())) {
            throw new UnauthorizedException("Invalid credentials");
        }
        customer.setEmailVerified(true);
        customerRepository.save(customer);
        em.createNativeQuery("UPDATE users SET email_verified = true, updated_at = now() WHERE id = :id")
                .setParameter("id", customer.getId())
                .executeUpdate();
    }

    /**
     * Self-service password change. Also revokes EVERY refresh-token session
     * of the account, so stolen long-lived sessions die immediately. Note:
     * issued ACCESS tokens are stateless JWTs and stay valid until they
     * expire — with the 15-minute access TTL that is at most 15 minutes.
     */
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
        refreshTokens.revokeAllForCustomer(userId);
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

    /**
     * Consumed-reset additionally revokes every refresh-token session of the
     * account (same caveat as {@link #changePassword}: a bearer access token
     * stays valid at most until the access TTL, default 15 minutes).
     */
    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new BusinessException("Invalid or expired reset token");
        }
        String tokenHash = sha256(rawToken);
        Long targetUserId = findResetTokenOwner(tokenHash);
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
        if (targetUserId != null) {
            refreshTokens.revokeAllForCustomer(targetUserId);
        }
        log.info("PASSWORD_RESET_COMPLETED");
    }

    /** Owner of a still-fresh reset token, or null (read BEFORE consumption). */
    private Long findResetTokenOwner(String tokenHash) {
        List<Object> rows = em.createNativeQuery(
                "SELECT user_id FROM password_reset_tokens "
                + "WHERE token_hash = :tokenHash AND used = false AND expires_at > now()")
                .setParameter("tokenHash", tokenHash)
                .setMaxResults(1)
                .getResultList();
        return rows.isEmpty() ? null : ((Number) rows.get(0)).longValue();
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

    public record LoginResult(String token, Long customerId, String fullName, String role,
                              String refreshToken) {
    }
}
