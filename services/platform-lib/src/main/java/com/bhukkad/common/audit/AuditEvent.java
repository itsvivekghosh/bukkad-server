package com.bhukkad.common.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Immutable, tamper-evident audit record (architecture-audit.md Section 3.3).
 *
 * <p>Every record is hash-chained to its predecessor so any later tampering is
 * detectable: {@code currentHash = SHA-256(prevHash + timestamp + actorId +
 * action + resourceType + resourceId + before + after + correlationId +
 * decision)}. The first record chains from a genesis previous hash.</p>
 *
 * <p>Intended to be written inside the calling business transaction (the same
 * unit of work as the audited change) and then shipped asynchronously to the
 * {@code audit.events} Kafka topic via the per-service outbox pattern.</p>
 */
@Entity
@Table(name = "audit_events", indexes = {
        @Index(name = "idx_audit_action_created", columnList = "action, createdAt"),
        @Index(name = "idx_audit_resource", columnList = "resourceType, resourceId")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
public class AuditEvent {

    /** Hash-chained predecessor of the very first record (genesis block). */
    public static final String GENESIS_PREV_HASH = "0000000000000000000000000000000000000000000000000000000000000000";

    @Id
    @Column(name = "id", updatable = false)
    private UUID id;

    @Column(nullable = false)
    private Instant timestamp;

    @Column(nullable = false, length = 128)
    private String actorId;

    @Column(nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private AuditEventAction action;

    @Column(nullable = false, length = 100)
    private String resourceType;

    @Column(nullable = false, length = 255)
    private String resourceId;

    @Column(columnDefinition = "TEXT")
    private String before; // JSON snapshot before the change

    @Column(columnDefinition = "TEXT")
    private String after; // JSON snapshot after the change

    @Column(length = 255)
    private String correlationId;

    @Column(nullable = false, length = 10)
    @Enumerated(EnumType.STRING)
    private AuditDecision decision;

    @Column(length = 64)
    private String prevHash; // SHA-256 of the previous record

    @Column(nullable = false, length = 64)
    private String currentHash; // SHA-256 of this record

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}