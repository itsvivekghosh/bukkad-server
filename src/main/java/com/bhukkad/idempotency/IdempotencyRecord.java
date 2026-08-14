package com.bhukkad.idempotency;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "idempotency_records", indexes = {
        @Index(name = "uk_idempotency_scope_key", columnList = "scope, idempotencyKey", unique = true),
        @Index(name = "idx_idempotency_expires", columnList = "expiresAt")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private IdempotencyScope scope;

    private Long ownerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IdempotencyStatus status;

    @Column(columnDefinition = "TEXT")
    private String responsePayload;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    public enum IdempotencyScope {
        ORDER_CREATE
    }

    public enum IdempotencyStatus {
        IN_PROGRESS, COMPLETED, FAILED
    }
}
