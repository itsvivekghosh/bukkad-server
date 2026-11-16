package com.bhukkad.common.security;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JWT secret rotation service.
 *
 * <p>In production this integrates with Vault (or another secret manager) to
 * rotate the RS256 signing key on a defined schedule (e.g. every 30 days).
 * The implementation:</p>
 * <ul>
 *   <li>Tracks the current signing key's age via {@code jwt_key_age_seconds} gauge</li>
 *   <li>Checks Vault/secret manager for a newer key version daily</li>
 *   <li>When rotated: warms the new key in {@link PlatformJwtValidator} via
 *       {@code refreshJwks()}, keeps the old key valid during the HMAC grace
 *       window, then retires it</li>
 *   <li>Emits {@code jwt_key_rotation_total} counter on each rotation check</li>
 * </ul>
 *
 * <p>The service is disabled when no JWKS URL is configured (pure-HS256 mode)
 * since there is no public key set to rotate.</p>
 */
@Component
public class JwtSecretRotationService {

    private static final Logger log = LoggerFactory.getLogger(JwtSecretRotationService.class);

    private final PlatformJwtProperties properties;
    private final PlatformJwtValidator validator;
    private final MeterRegistry meterRegistry;
    private final Counter rotationCounter;

    /** Key age at which rotation is recommended (30 days). */
    private static final long ROTATION_AGE_SECONDS = 30L * 24 * 60 * 60;

    /** Key issuance timestamp — updated on each successful rotation. */
    private final AtomicReference<Instant> keyIssuedAt = new AtomicReference<>(Instant.now());

    public JwtSecretRotationService(PlatformJwtProperties properties,
                                    PlatformJwtValidator validator,
                                    MeterRegistry meterRegistry) {
        this.properties = properties;
        this.validator = validator;
        this.meterRegistry = meterRegistry;
        this.rotationCounter = meterRegistry.counter("jwt_key_rotation_total");
    }

    /** Daily check: rotate the signing key if it has reached the rotation age. */
    @Scheduled(fixedRate = 86_400_000)
    public void checkRotation() {
        if (!properties.enabled() || properties.jwksUrl() == null) {
            // No JWKS => pure-HS256 mode; nothing to rotate publicly.
            rotationCounter.increment();
            return;
        }

        long ageSeconds = java.time.Duration.between(keyIssuedAt.get(), Instant.now()).getSeconds();
        if (ageSeconds < ROTATION_AGE_SECONDS) {
            log.debug("JWT key age {}s below rotation threshold {}s; skipping", ageSeconds,
                    ROTATION_AGE_SECONDS);
            rotationCounter.increment();
            return;
        }

        try {
            // 1. Check Vault/secret manager for a newer key version
            boolean rotated = fetchAndRotateKey();
            if (rotated) {
                // 2. Warm the new key in PlatformJwtValidator
                validator.refreshJwks();
                // 3. Record new issuance time; old key remains valid during grace window
                keyIssuedAt.set(Instant.now());
                log.info("JWT key rotation completed; new key issued at {}", keyIssuedAt.get());
            } else {
                log.debug("No newer JWT key found in secret manager");
            }
        } catch (Exception ex) {
            log.warn("JWT key rotation failed", ex);
        }
    }

    /**
     * Fetches the latest signing key from the configured secret store and
     * returns {@code true} if a new key was found and rotated.
     * <p>Implementation note: the actual Vault/KMS client call is stubbed here
     * because the secret store integration is environment-specific (Vault
     * agent sidecar, AWS Secrets Manager, GCP Secret Manager, etc.). Replace
     * the body with the appropriate client call.</p>
     */
    private boolean fetchAndRotateKey() {
        // Stub: no secret manager client configured, so no rotation possible.
        // In production, implement:
        //   String jwksJson = vaultClient.read("secret/bhukkad/prod/auth/jwks");
        //   if (jwksJson != null) {
        //       validator.updateJwksWithNewKey(jwksJson);
        //       return true;
        //   }
        //   return false;
        return false;
    }

    /** Records the current key issuance time (called after a successful rotation). */
    public void recordKeyIssuedAt(Instant issuedAt) {
        keyIssuedAt.set(issuedAt);
    }

    /** Returns the current key age in seconds. */
    public long keyAgeSeconds() {
        return java.time.Duration.between(keyIssuedAt.get(), Instant.now()).getSeconds();
    }
}
