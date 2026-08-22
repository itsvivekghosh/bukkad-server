package com.bhukkad.security;

import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Holds the JWT signing secret plus a short grace period of the previous
 * secret so tokens issued just before a rotation remain valid until they
 * expire. A scheduled task rotates the active secret on a configurable
 * interval.
 *
 * <p>The active secret is generated locally (seeded from the configured
 * bootstrap secret) so multiple instances converge on the same value only
 * if they share the same seed — for a single-instance deployment this gives
 * automated rotation without external infrastructure. In a clustered
 * deployment, point {@code app.jwt.rotation.source} at a shared secret
 * store instead.
 */
@Slf4j
@Service
public class JwtSecretRotationService {

    private static final int ROTATED_SECRETS_TO_KEEP = 2;

    private final List<SecretKey> validKeys = new CopyOnWriteArrayList<>();
    private final String bootstrapSecret;

    public JwtSecretRotationService(@Value("${app.jwt.secret}") String bootstrapSecret,
                                    @Value("${app.jwt.rotation.enabled:false}") boolean rotationEnabled) {
        this.bootstrapSecret = bootstrapSecret;
        this.validKeys.add(keyFrom(bootstrapSecret));
        if (rotationEnabled) {
            rotateNow();
        }
    }

    /** Current signing key (always the newest). */
    public SecretKey currentSigningKey() {
        return validKeys.get(validKeys.size() - 1);
    }

    /** All keys that may still validate inbound tokens. */
    public List<SecretKey> validationKeys() {
        return List.copyOf(validKeys);
    }

    @Scheduled(fixedDelayString = "${app.jwt.rotation.interval-ms:86400000}")
    public void scheduledRotation() {
        rotateNow();
    }

    void rotateNow() {
        String newSecret = Base64.getEncoder().encodeToString(
                java.security.SecureRandom.getSeed(48));
        validKeys.add(keyFrom(newSecret));
        while (validKeys.size() > ROTATED_SECRETS_TO_KEEP) {
            validKeys.remove(0);
        }
        log.info("JWT_SECRET_ROTATED | activeKeys={}", validKeys.size());
    }

    private static SecretKey keyFrom(String secret) {
        byte[] keyBytes = Decoders.BASE64.decode(secret);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
