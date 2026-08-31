package com.bhukkad.admin.service;

import com.bhukkad.admin.domain.ApiKey;
import com.bhukkad.admin.domain.ApiKeyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * API-key issuance + validation (port of monolith {@code ApiKeyService}).
 * Only the SHA-256 hash is persisted; the raw key is returned once.
 */
@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private final ApiKeyRepository apiKeyRepository;

    @Transactional
    public IssuedKey create(String name, int ttlDays) {
        String raw = "bhk-" + UUID.randomUUID();
        ApiKey key = new ApiKey();
        key.setKeyHash(hash(raw));
        key.setName(name);
        key.setStatus(ApiKey.STATUS_ACTIVE);
        key.setExpiresAt(LocalDateTime.now().plusDays(ttlDays));
        apiKeyRepository.save(key);
        return new IssuedKey(key.getId(), raw, key.getExpiresAt());
    }

    @Transactional(readOnly = true)
    public boolean isValid(String rawKey) {
        return apiKeyRepository.findByKeyHashAndStatus(hash(rawKey), ApiKey.STATUS_ACTIVE)
                .map(k -> k.getExpiresAt().isAfter(LocalDateTime.now()))
                .orElse(false);
    }

    private String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record IssuedKey(Long id, String rawKey, LocalDateTime expiresAt) {
    }
}