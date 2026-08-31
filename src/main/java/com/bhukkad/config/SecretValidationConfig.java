package com.bhukkad.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

@Slf4j
@Configuration
@Profile({"prod", "staging"})
public class SecretValidationConfig {

    /** HS512 signing requires at least 512 bits (64 bytes) of decoded key material. */
    private static final int MIN_JWT_SECRET_BYTES = 64;

    private static final Set<String> WEAK_JWT_SECRETS = Set.of(
            "Wz6ZY4rjTh8TXTAsD25DB3+vPAnlJtL8iIms5CgJpWQqK6ZwXW6u+1PxMmNOj7N0akCSkweqlxighGqSKosZsA==",
            "changeme",
            "secret",
            "your-secret-key",
            "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970"
    );

    private static final Set<String> WEAK_DB_PASSWORDS = Set.of(
            "root",
            "password",
            "changeme",
            "Vivek@1999"
    );

    private static final Set<String> WEAK_API_KEY_PEPPERS = Set.of(
            "zYHsmVfyqzrgU+RE/SXJ+Ep59FZMasTNccKToJT9yKpc6Ntk15gfWXCaPV3sGJr0Vw7X5uKeRIuaRUq4VKQECQ==",
            "changeme",
            "secret",
            "your-pepper"
    );

    @Value("${app.jwt.secret:}")
    private String jwtSecret;

    @Value("${spring.datasource.password:}")
    private String dbPassword;

    @Value("${app.api-key.pepper:}")
    private String apiKeyPepper;

    /**
     * Production must run the real Razorpay gateway. The simulated gateway has
     * no shared secret to validate webhook signatures against, so with it
     * active any caller could POST a forged {@code payment.captured} webhook
     * and mark orders paid. Refuse to boot in prod/staging unless the real
     * gateway is enabled.
     */
    @Value("${app.payment.razorpay.enabled:false}")
    private boolean razorpayEnabled;

    @PostConstruct
    void validateRequiredSecrets() {
        List<String> violations = new ArrayList<>();

        if (!razorpayEnabled) {
            violations.add("app.payment.razorpay.enabled must be true in production (simulated gateway is not secure)");
        }

        if (!StringUtils.hasText(jwtSecret)) {
            violations.add("JWT_SECRET is required in production");
        } else if (WEAK_JWT_SECRETS.contains(jwtSecret)) {
            violations.add("JWT_SECRET must not use a default or weak value in production");
        } else {
            try {
                int decodedBytes = Base64.getDecoder().decode(jwtSecret).length;
                if (decodedBytes < MIN_JWT_SECRET_BYTES) {
                    violations.add("JWT_SECRET must decode to at least "
                            + MIN_JWT_SECRET_BYTES + " bytes (512 bits) for HS512 in production");
                }
            } catch (IllegalArgumentException e) {
                violations.add("JWT_SECRET must be a valid base64 value in production");
            }
        }

        if (!StringUtils.hasText(dbPassword)) {
            violations.add("DB_PASSWORD is required in production");
        } else if (WEAK_DB_PASSWORDS.contains(dbPassword)) {
            violations.add("DB_PASSWORD must not use a default or weak value in production");
        }

        if (!StringUtils.hasText(apiKeyPepper)) {
            violations.add("API_KEY_PEPPER is required in production");
        } else if (WEAK_API_KEY_PEPPERS.contains(apiKeyPepper)) {
            violations.add("API_KEY_PEPPER must not use a default or weak value in production");
        }

        if (!violations.isEmpty()) {
            String message = String.join("; ", violations);
            log.error("SECRET_VALIDATION_FAILED | {}", message);
            throw new IllegalStateException("Production secret validation failed: " + message);
        }

        log.info("SECRET_VALIDATION_PASSED | production secrets configured");
    }
}
