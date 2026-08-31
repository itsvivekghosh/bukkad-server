package com.bhukkad.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

/**
 * Provides the shared {@link PasswordEncoder} bean with configurable algorithm
 * and parameters.
 *
 * <p>Defined in its own configuration class (rather than in
 * {@link org.springframework.security.config.annotation.web.configuration.WebSecurityConfig})
 * to break a circular reference: {@link SecurityConfig} depends on
 * {@code OAuth2LoginSuccessHandler}, which depends on the
 * {@code PasswordEncoder} bean. If the encoder were declared inside
 * {@link SecurityConfig}, Spring could not create it while
 * {@link SecurityConfig} was still being constructed.
 *
 * <p><strong>Hashing scheme:</strong> new passwords are hashed with
 * <strong>Argon2id</strong> ({@code {argon2}} prefix, configurable params).
 * Legacy hashes produced by the old plain BCrypt encoder remain verifiable:
 * {@code DelegatingPasswordEncoder} matches them through its default
 * {@link BCryptPasswordEncoder} (both the unprefixed {@code $2a$...} format
 * used before this upgrade and explicit {@code {bcrypt}} prefixes).
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.security.password-encoder")
    public PasswordEncoderProperties passwordEncoderProperties() {
        return new PasswordEncoderProperties();
    }

    @Bean
    public PasswordEncoder passwordEncoder(PasswordEncoderProperties properties) {
        String algorithm = properties.getAlgorithm() != null ? properties.getAlgorithm() : "argon2";
        Map<String, PasswordEncoder> encoders = new java.util.HashMap<>();

        if ("bcrypt".equalsIgnoreCase(algorithm)) {
            // BCrypt-only mode: use BCrypt as primary encoder.
            encoders.put("bcrypt", new BCryptPasswordEncoder(properties.getBcrypt().getStrength()));
            DelegatingPasswordEncoder encoder = new DelegatingPasswordEncoder("bcrypt", encoders);
            encoder.setDefaultPasswordEncoderForMatches(new BCryptPasswordEncoder());
            return encoder;
        }

        // Default: Argon2id primary, BCrypt fallback for legacy hashes.
        PasswordEncoderProperties.Argon2 argon2 = properties.getArgon2();
        Argon2PasswordEncoder argon2Encoder = new Argon2PasswordEncoder(
                argon2.getSaltLength(),
                argon2.getHashLength(),
                argon2.getParallelism(),
                argon2.getMemoryKib(),
                argon2.getIterations());
        encoders.put("argon2", argon2Encoder);
        encoders.put("bcrypt", new BCryptPasswordEncoder(properties.getBcrypt().getStrength()));

        DelegatingPasswordEncoder encoder = new DelegatingPasswordEncoder("argon2", encoders);
        encoder.setDefaultPasswordEncoderForMatches(new BCryptPasswordEncoder());
        return encoder;
    }
}
