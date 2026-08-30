package com.bhukkad.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Validates JWE nonces to prevent replay attacks.
 *
 * <p>Each encrypted payload includes a unique nonce. After successful decryption,
 * the nonce is checked against a Redis SET with a short TTL (matching the
 * timestamp skew window). If the nonce already exists, the request is rejected.
 *
 * <p>TTL is intentionally short (5 minutes) — once a nonce is accepted, it is
 * blacklisted for slightly longer than the server's maximum timestamp skew,
 * ensuring a captured token cannot be replayed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReplayNonceValidator {

    private static final String NONCE_KEY_PREFIX = "auth:nonce:";
    private static final Duration NONCE_TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redisTemplate;

    /**
     * Validates that the given nonce has not been used before.
     *
     * @param nonce the nonce to validate
     * @return true if the nonce is valid (first use), false if it has been used
     * @throws SecurityException if the nonce has already been consumed (replay)
     */
    public void validateNonce(String nonce) {
        String key = NONCE_KEY_PREFIX + nonce;
        // SET NX — atomically claim the nonce. If it already exists, reject.
        Boolean set = redisTemplate.opsForValue()
                .setIfAbsent(key, "1", NONCE_TTL);
        if (set == null || !set) {
            log.warn("REPLAY_NONCE_REJECTED | nonce={}", nonce);
            throw new SecurityException("Nonce has already been used (replay detected)");
        }
    }
}
