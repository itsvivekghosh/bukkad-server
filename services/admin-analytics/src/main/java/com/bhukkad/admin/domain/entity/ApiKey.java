package com.bhukkad.admin.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * Partner API key (admin-analytics read model). Only the SHA-256 hash of the
 * full key is stored; the plaintext key is returned to the caller exactly once
 * at creation time. Lives next to {@code ApiKeyService} which mints and
 * validates them; the partner-facing controller
 * ({@code ApiKeyAdminController}) exposes CRUD.
 */
@Entity
@Table(name = "api_keys", indexes = {
        @Index(name = "uk_api_key_hash", columnList = "keyHash", unique = true)
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
public class ApiKey {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_REVOKED = "REVOKED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    /** SHA-256 hex digest of the full key. */
    @Column(nullable = false, length = 64)
    private String keyHash;

    @Column(nullable = false, length = 20)
    private String status = STATUS_ACTIVE;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime expiresAt;
}
