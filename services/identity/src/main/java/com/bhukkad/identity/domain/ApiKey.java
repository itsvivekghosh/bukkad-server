package com.bhukkad.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "api_keys", indexes = {
        @Index(name = "idx_api_keys_status", columnList = "status"),
        @Index(name = "idx_api_keys_partner", columnList = "partnerId")
})
@Getter
@Setter
public class ApiKey {

    public enum KeyStatus {
        ACTIVE, REVOKED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "api_key_seq")
    @SequenceGenerator(name = "api_key_seq", sequenceName = "api_key_seq", allocationSize = 1)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 20)
    private String keyPrefix;

    @Column(nullable = false, length = 64)
    private String keyHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private KeyStatus status = KeyStatus.ACTIVE;

    private Long partnerId;

    @Column(length = 500)
    private String scopes;

    private java.time.LocalDateTime expiresAt;

    private java.time.LocalDateTime lastUsedAt;

    @Column(nullable = false)
    private java.time.LocalDateTime createdAt;

    private java.time.LocalDateTime revokedAt;
}
