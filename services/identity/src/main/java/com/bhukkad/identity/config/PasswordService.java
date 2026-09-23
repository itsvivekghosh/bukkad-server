package com.bhukkad.identity.config;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Delegates to the shared platform-lib {@link PasswordEncoder} so identity
 * hashes new credentials with Argon2id (primary) and verifies legacy BCrypt
 * hashes via the {@code DelegatingPasswordEncoder} fallback.
 */
@Service
public class PasswordService {

    private final PasswordEncoder encoder;

    public PasswordService(PasswordEncoder encoder) {
        this.encoder = encoder;
    }

    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    public boolean matches(String rawPassword, String storedHash) {
        return encoder.matches(rawPassword, storedHash);
    }
}
