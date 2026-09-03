package com.bhukkad.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * JWT secret rotation watchdog.
 *
 * <p>In production this would integrate with Vault (or another secret manager)
 * to rotate the signing key on a defined schedule (e.g. every 30 days). The
 * current implementation logs a reminder and exposes the current secret's age
 * via a metric placeholder.</p>
 */
@Component
public class JwtSecretRotationService {

    private static final Logger log = LoggerFactory.getLogger(JwtSecretRotationService.class);

    private final PlatformJwtProperties properties;

    public JwtSecretRotationService(PlatformJwtProperties properties) {
        this.properties = properties;
    }

    @Scheduled(fixedRate = 86_400_000) // daily
    public void checkRotation() {
        log.info("JWT secret rotation check: secret is configured={}",
                properties.secret() != null && !properties.secret().isBlank());
        // Real implementation would:
        // 1. Check Vault for a newer secret version
        // 2. If rotated, warm the new key in PlatformJwtValidator
        // 3. Keep the old key valid for a grace period
        // 4. Update the active secret atomically
    }
}
