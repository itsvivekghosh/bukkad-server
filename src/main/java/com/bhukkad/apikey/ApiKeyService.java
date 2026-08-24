package com.bhukkad.apikey;

import com.bhukkad.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.codec.Hex;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Manages partner API keys. Full keys are shown exactly once at creation;
 * only their SHA-256 digest is persisted. A key looks like
 * {@code bhk_<prefix>_<secret>} where the prefix allows a quick lookup
 * before the (slower) hash comparison.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private static final String KEY_PREFIX_SEED = "bhk_";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiKeyRepository apiKeyRepository;

    /**
     * Creates a key and returns the plaintext secret. Callers must store it
     * immediately; it cannot be retrieved later.
     */
    @Transactional
    public CreatedApiKey create(String name, Long partnerId, String scopes, LocalDateTime expiresAt) {
        String prefix = randomPart(6);
        String secret = randomPart(24);
        String fullKey = KEY_PREFIX_SEED + prefix + "_" + secret;

        ApiKey key = new ApiKey();
        key.setName(name);
        key.setKeyPrefix(prefix);
        key.setKeyHash(hash(fullKey));
        key.setPartnerId(partnerId);
        key.setScopes(scopes);
        key.setExpiresAt(expiresAt);
        key.setStatus(ApiKey.ApiKeyStatus.ACTIVE);
        apiKeyRepository.save(key);

        log.info("API_KEY_CREATED | id={} | name={} | partnerId={}", key.getId(), name, partnerId);
        return new CreatedApiKey(key.getId(), name, fullKey, scopes, expiresAt);
    }

    @Transactional(readOnly = true)
    public List<ApiKey> list() {
        return apiKeyRepository.findAll();
    }

    @Transactional
    public void revoke(Long id) {
        ApiKey key = apiKeyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("API key not found"));
        key.setStatus(ApiKey.ApiKeyStatus.REVOKED);
        key.setRevokedAt(LocalDateTime.now());
        apiKeyRepository.save(key);
        log.info("API_KEY_REVOKED | id={} | name={}", id, key.getName());
    }

    /** Rotates a key: revokes the old one and issues a fresh secret. */
    @Transactional
    public CreatedApiKey rotate(Long id, String scopes, LocalDateTime expiresAt) {
        ApiKey existing = apiKeyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("API key not found"));
        String name = existing.getName();
        Long partnerId = existing.getPartnerId();
        revoke(id);
        return create(name, partnerId, scopes != null ? scopes : existing.getScopes(), expiresAt);
    }

    /**
     * Validates a presented key. Returns the key record on success, or empty
     * if unknown, revoked or expired.
     */
    @Transactional
    public Optional<ApiKey> validate(String presentedKey) {
        if (presentedKey == null || !presentedKey.startsWith(KEY_PREFIX_SEED)) {
            return Optional.empty();
        }
        Optional<ApiKey> byHash = apiKeyRepository.findByKeyHash(hash(presentedKey));
        if (byHash.isEmpty()) {
            return Optional.empty();
        }
        ApiKey key = byHash.get();
        if (!key.isActive()) {
            return Optional.empty();
        }
        // Update last-used timestamp opportunistically (best effort).
        try {
            key.setLastUsedAt(LocalDateTime.now());
            apiKeyRepository.save(key);
        } catch (Exception ignored) {
            // non-critical bookkeeping
        }
        return Optional.of(key);
    }

    private static String randomPart(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return new String(Hex.encode(digest.digest(value.getBytes(StandardCharsets.UTF_8))));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record CreatedApiKey(Long id, String name, String apiKey, String scopes, LocalDateTime expiresAt) {
    }
}
