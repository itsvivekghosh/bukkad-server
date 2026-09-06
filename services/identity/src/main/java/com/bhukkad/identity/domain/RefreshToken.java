package com.bhukkad.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Server-side refresh-token session row. Only the SHA-256 hex hash of the
 * 256-bit random token is stored — the raw value exists just in the response
 * body, so a database leak cannot mint sessions. Rotation keeps the original
 * {@code expiresAt} (absolute session lifetime): refreshing cannot extend a
 * session past its issue-time horizon.
 */
@Entity
@Table(name = "refresh_tokens", indexes = {
        @Index(name = "idx_refresh_tokens_customer", columnList = "customer_id"),
        @Index(name = "idx_refresh_tokens_expires", columnList = "expires_at")
})
@Getter
@Setter
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning principal id (shares the users/customers id space). */
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static RefreshToken of(Long customerId, String tokenHash, Instant expiresAt) {
        RefreshToken token = new RefreshToken();
        token.setCustomerId(customerId);
        token.setTokenHash(tokenHash);
        token.setExpiresAt(expiresAt);
        return token;
    }

    /** True while unrevoked and within the expiry window (checked at {@code now}). */
    public boolean isActiveAt(Instant now) {
        return revokedAt == null && expiresAt != null && expiresAt.isAfter(now);
    }

    @PrePersist
    void stampCreatedAt() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
