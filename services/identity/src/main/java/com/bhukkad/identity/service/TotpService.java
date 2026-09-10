package com.bhukkad.identity.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.common.util.TOTPGenerator;
import com.bhukkad.identity.domain.Admin;
import com.bhukkad.identity.domain.AdminRepository;
import com.bhukkad.identity.domain.Customer;
import com.bhukkad.identity.domain.CustomerRepository;
import com.bhukkad.identity.domain.User;
import com.bhukkad.identity.domain.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * TOTP MFA enrollment and verification (feature #5). Uses the platform-lib
 * {@link TOTPGenerator} (RFC 6238-style, HMAC-SHA256, ±1 step drift window).
 *
 * <p>Flow: {@code enroll} generates a fresh base32 secret, stores it on the
 * user's profile row and returns the otpauth:// provisioning URI ONCE (the
 * client renders the QR from it). {@code confirm} verifies a live code and
 * flips {@code users.totp_enabled}, stamping {@code users.totp_confirmed_at}.
 * From then on login requires a valid {@code totpCode}. The secret is never
 * logged and never returned after the enrollment response.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TotpService {

    /** otpauth URI issuer shown in authenticator apps. */
    private static final String OTPAUTH_ISSUER = "Bhukkad";

    private final CustomerRepository customerRepository;
    private final AdminRepository adminRepository;
    private final UserRepository userRepository;

    public record Enrollment(String otpauthUri) {
    }

    /**
     * Generates (or rotates) the TOTP secret for the principal's profile row
     * and returns the otpauth provisioning URI. Any previous enrollment is
     * replaced and {@code totp_enabled} resets until the new secret is
     * confirmed with a live code.
     */
    @Transactional
    public Enrollment enroll(Long userId, String email) {
        String secret = TOTPGenerator.generateSecret();
        storeSecret(userId, secret);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        user.setTotpEnabled(false);
        user.setTotpConfirmedAt(null);
        userRepository.save(user);
        log.info("TOTP enrollment (re)generated for userId={} — secret never logged", userId);
        return new Enrollment(TOTPGenerator.otpauthUri(secret, email, OTPAUTH_ISSUER));
    }

    /** Confirms an enrollment with a live code; enables TOTP on the account. */
    @Transactional
    public void confirm(Long userId, String code) {
        String secret = secretOf(userId);
        if (secret == null || secret.isBlank()) {
            throw new BusinessException("No TOTP enrollment in progress");
        }
        if (code == null || !TOTPGenerator.verify(secret, code, 1)) {
            throw new BusinessException("Invalid TOTP code");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        user.setTotpEnabled(true);
        user.setTotpConfirmedAt(LocalDateTime.now());
        userRepository.save(user);
        log.info("TOTP confirmed for userId={}", userId);
    }

    /** Whether the account requires a TOTP code at login. */
    @Transactional(readOnly = true)
    public boolean isRequired(Long userId) {
        return userRepository.findById(userId)
                .map(user -> Boolean.TRUE.equals(user.getTotpEnabled()))
                .orElse(false);
    }

    /** Login-time verification against the stored secret (±1 step drift). */
    @Transactional(readOnly = true)
    public boolean verifyLoginCode(Long userId, String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        String secret = secretOf(userId);
        return secret != null && TOTPGenerator.verify(secret, code, 1);
    }

    private void storeSecret(Long userId, String secret) {
        Customer customer = customerRepository.findById(userId).orElse(null);
        if (customer != null) {
            customer.setTotpSecret(secret);
            customerRepository.save(customer);
            return;
        }
        Admin admin = adminRepository.findById(userId).orElse(null);
        if (admin != null) {
            admin.setTotpSecret(secret);
            adminRepository.save(admin);
            return;
        }
        throw new ResourceNotFoundException("User not found: " + userId);
    }

    private String secretOf(Long userId) {
        Customer customer = customerRepository.findById(userId).orElse(null);
        if (customer != null) {
            return customer.getTotpSecret();
        }
        Admin admin = adminRepository.findById(userId).orElse(null);
        return admin == null ? null : admin.getTotpSecret();
    }
}
