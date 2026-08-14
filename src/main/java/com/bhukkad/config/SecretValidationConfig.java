package com.bhukkad.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
@Configuration
@Profile("prod")
public class SecretValidationConfig {

    private static final Set<String> WEAK_JWT_SECRETS = Set.of(
            "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970",
            "changeme",
            "secret",
            "your-secret-key"
    );

    private static final Set<String> WEAK_DB_PASSWORDS = Set.of(
            "root",
            "password",
            "changeme",
            "Vivek@1999"
    );

    @Value("${app.jwt.secret:}")
    private String jwtSecret;

    @Value("${spring.datasource.password:}")
    private String dbPassword;

    @PostConstruct
    void validateRequiredSecrets() {
        List<String> violations = new ArrayList<>();

        if (!StringUtils.hasText(jwtSecret)) {
            violations.add("JWT_SECRET is required in production");
        } else if (jwtSecret.length() < 32) {
            violations.add("JWT_SECRET must be at least 32 characters in production");
        } else if (WEAK_JWT_SECRETS.contains(jwtSecret)) {
            violations.add("JWT_SECRET must not use a default or weak value in production");
        }

        if (!StringUtils.hasText(dbPassword)) {
            violations.add("DB_PASSWORD is required in production");
        } else if (WEAK_DB_PASSWORDS.contains(dbPassword)) {
            violations.add("DB_PASSWORD must not use a default or weak value in production");
        }

        if (!violations.isEmpty()) {
            String message = String.join("; ", violations);
            log.error("SECRET_VALIDATION_FAILED | {}", message);
            throw new IllegalStateException("Production secret validation failed: " + message);
        }

        log.info("SECRET_VALIDATION_PASSED | production secrets configured");
    }
}
