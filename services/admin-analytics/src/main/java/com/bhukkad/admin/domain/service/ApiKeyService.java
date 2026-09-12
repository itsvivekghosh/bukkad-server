package com.bhukkad.admin.domain.service;

import com.bhukkad.admin.domain.entity.ApiKey;
import com.bhukkad.admin.domain.repository.ApiKeyRepository;
import com.bhukkad.common.scan.AllowFullScan;
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

    /**
     * Safe listing: names and status only — hashes never leave the vault.
     * PERF-3: the vault was listed whole-table; bounded to the newest 200 keys
     * with ORDER BY created_at DESC in SQL (bare-list shape kept, additively
     * capped).
     */
    @Transactional(readOnly = true)
    @AllowFullScan(reason = "G-6 reviewed: SQL-side page of LIST_PAGE_CAP newest keys, ordered by createdAt")
    public java.util.List<ApiKeyView> list() {
        return apiKeyRepository.findAll(org.springframework.data.domain.PageRequest.of(
                        0, AdminQueryService.LIST_PAGE_CAP,
                        org.springframework.data.domain.Sort.by(
                                org.springframework.data.domain.Sort.Direction.DESC, "createdAt")))
                .getContent()
                .stream()
                .map(k -> new ApiKeyView(k.getId(), k.getName(), k.getStatus(),
                        k.getCreatedAt(), k.getExpiresAt()))
                .toList();
    }

    public record ApiKeyView(Long id, String name, String status,
                             LocalDateTime createdAt, LocalDateTime expiresAt) {
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