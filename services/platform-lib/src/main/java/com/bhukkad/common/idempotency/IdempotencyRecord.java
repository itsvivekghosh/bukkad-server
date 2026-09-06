package com.bhukkad.common.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * Per-service idempotency record (plan §6.1/§7): the {@code (scope, key)}
 * unique constraint is the authoritative first-write-wins guard that every
 * idempotent consumer/request path relies on. At-least-once delivery + these
 * records give exactly-once processing semantics per key.
 */
@Entity(name = "CommonIdempotencyRecord")
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
        ORDER_CREATE, BATCH_ORDER_CREATE, PAYMENT_PROCESS, RAZORPAY_WEBHOOK, KAFKA_CONSUME
    }

    public enum IdempotencyStatus {
        IN_PROGRESS, COMPLETED, FAILED
    }
}
