package com.bhukkad.apikey;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * Partner API key. Only the SHA-256 hash of the full key is stored; the
 * plaintext key is returned to the caller exactly once at creation time.
 */
@Entity
@Table(name = "api_keys", indexes = {
        @Index(name = "uk_api_keys_prefix", columnList = "keyPrefix", unique = true),
        @Index(name = "idx_api_keys_status", columnList = "status"),
        @Index(name = "idx_api_keys_partner", columnList = "partnerId")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 12)
    private String keyPrefix;

    /** SHA-256 hex digest of the full key. */
    @Column(nullable = false, length = 64)
    private String keyHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ApiKeyStatus status = ApiKeyStatus.ACTIVE;

    private Long partnerId;

    @Column(length = 255)
    private String scopes;

    private LocalDateTime expiresAt;

    private LocalDateTime lastUsedAt;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime revokedAt;

    public boolean isActive() {
        return status == ApiKeyStatus.ACTIVE
                && (expiresAt == null || expiresAt.isAfter(LocalDateTime.now()));
    }

    public enum ApiKeyStatus {
        ACTIVE, REVOKED
    }
}
