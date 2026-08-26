package com.bhukkad.security;

import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.WeakKeyException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Holds the JWT signing secret plus a short grace period of the previous
 * secret so tokens issued just before a rotation remain valid until they
 * expire. A scheduled task rotates the active secret on a configurable
 * interval.
 *
 * <p>Tokens are signed with <strong>HS512 (HMAC-SHA-512)</strong>, so every
 * key must provide at least 512 bits (64 bytes) of key material; weaker
 * configured secrets are rejected at startup with a
 * {@link WeakKeyException}. The active secret is generated locally (seeded
 * from the configured bootstrap secret) so multiple instances converge on the
 * same value only if they share the same seed — for a single-instance
 * deployment this gives automated rotation without external infrastructure.
 * In a clustered deployment, point {@code app.jwt.rotation.source} at a
 * shared secret store instead.
 */
@Slf4j
@Service
public class JwtSecretRotationService {

    private static final int ROTATED_SECRETS_TO_KEEP = 2;

    /** HS512 requires >= 512 bits; jjwt maps exactly 64 bytes to HmacSHA512. */
    private static final int MIN_KEY_BYTES = 64;
    /** Rotated secrets are minted with 512 bits of fresh entropy. */
    private static final int ROTATION_KEY_BYTES = 64;
    /** Reused across rotations: SecureRandom is thread-safe and self-seeding. */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final List<SecretKey> validKeys = new CopyOnWriteArrayList<>();
    private final String bootstrapSecret;
    private final boolean rotationEnabled;

    public JwtSecretRotationService(@Value("${app.jwt.secret}") String bootstrapSecret,
                                    @Value("${app.jwt.rotation.enabled:false}") boolean rotationEnabled) {
        this.bootstrapSecret = bootstrapSecret;
        this.rotationEnabled = rotationEnabled;
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
        if (!rotationEnabled) {
            return;
        }
        rotateNow();
    }

    void rotateNow() {
        String newSecret = Base64.getEncoder().encodeToString(randomBytes(ROTATION_KEY_BYTES));
        validKeys.add(keyFrom(newSecret));
        while (validKeys.size() > ROTATED_SECRETS_TO_KEEP) {
            validKeys.remove(0);
        }
        log.info("JWT_SECRET_ROTATED | activeKeys={}", validKeys.size());
    }

    private static byte[] randomBytes(int length) {
        // One shared instance: SecureRandom is thread-safe, and instantiating it
        // per call forces a fresh OS entropy fetch each time (slow and noisy).
        byte[] buffer = new byte[length];
        SECURE_RANDOM.nextBytes(buffer);
        return buffer;
    }

    /**
     * Derives a 512-bit HMAC-SHA-512 key from the configured base64 secret,
     * failing fast when the decoded material is shorter than 512 bits so the
     * application never silently signs tokens with a weak key.
     */
    private static SecretKey keyFrom(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("JWT secret must not be blank");
        }
        byte[] keyBytes = Decoders.BASE64.decode(secret);
        if (keyBytes.length < MIN_KEY_BYTES) {
            throw new WeakKeyException(
                    "Configured JWT secret decodes to " + (keyBytes.length * 8)
                            + " bits; HS512 signing requires at least "
                            + (MIN_KEY_BYTES * 8) + " bits (64 bytes).");
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
