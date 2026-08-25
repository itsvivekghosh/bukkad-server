package com.bhukkad.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashMap;
import java.util.Map;

/**
 * Provides the shared {@link PasswordEncoder} bean.
 *
 * <p>Defined in its own configuration class (rather than in
 * {@link SecurityConfig}) to break a circular reference: {@link SecurityConfig}
 * depends on {@code OAuth2LoginSuccessHandler}, which depends on the
 * {@code PasswordEncoder} bean. If the encoder were declared inside
 * {@link SecurityConfig}, Spring could not create it while
 * {@link SecurityConfig} was still being constructed.
 *
 * <p><strong>Hashing scheme:</strong> new passwords are hashed with
 * <strong>Argon2id</strong> ({@code {argon2}} prefix, 64 MiB memory, 3
 * iterations — OWASP-recommended parameters). Legacy hashes produced by the
 * old plain BCrypt encoder remain verifiable: {@code DelegatingPasswordEncoder}
 * matches them through its default {@link BCryptPasswordEncoder} (both the
 * unprefixed {@code $2a$...} format used before this upgrade and explicit
 * {@code {bcrypt}} prefixes). Callers should re-encode stored hashes to
 * Argon2id on successful login (see {@code AuthServiceImpl}).
 */
@Configuration
public class PasswordEncoderConfig {

    /** Argon2id: 16-byte salt, 32-byte hash, 1 lane, 64 MiB, 3 iterations. */
    private static final int ARGON2_SALT_LENGTH = 16;
    private static final int ARGON2_HASH_LENGTH = 32;
    private static final int ARGON2_PARALLELISM = 1;
    private static final int ARGON2_MEMORY_KIB = 64 * 1024;
    private static final int ARGON2_ITERATIONS = 3;

    @Bean
    public PasswordEncoder passwordEncoder() {
        Map<String, PasswordEncoder> encoders = new HashMap<>();
        encoders.put("argon2", new Argon2PasswordEncoder(
                ARGON2_SALT_LENGTH, ARGON2_HASH_LENGTH, ARGON2_PARALLELISM,
                ARGON2_MEMORY_KIB, ARGON2_ITERATIONS));
        encoders.put("bcrypt", new BCryptPasswordEncoder(12));

        DelegatingPasswordEncoder encoder = new DelegatingPasswordEncoder("argon2", encoders);
        // Legacy hashes written by the pre-upgrade BCryptPasswordEncoder carry no
        // {id} prefix; delegate them to BCrypt so existing accounts keep working.
        encoder.setDefaultPasswordEncoderForMatches(new BCryptPasswordEncoder());
        return encoder;
    }
}
