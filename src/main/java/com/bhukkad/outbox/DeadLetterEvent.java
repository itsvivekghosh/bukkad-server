package com.bhukkad.outbox;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * Dead-letter record for outbox events that exhausted their retry budget.
 * Kept for audit and manual re-drive; events remain in the outbox table
 * marked FAILED so the original row is never lost.
 */
@Entity
@Table(name = "dead_letter_events", indexes = {
        @Index(name = "idx_dlq_status_created", columnList = "status, createdAt"),
        @Index(name = "idx_dlq_aggregate", columnList = "aggregateType, aggregateId")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
public class DeadLetterEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String eventType;

    @Column(nullable = false, length = 50)
    private String aggregateType;

    @Column(nullable = false)
    private Long aggregateId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(length = 1000)
    private String lastError;

    @Column(nullable = false)
    private int retryCount;

    @Column(length = 20)
    private String source; // e.g. OUTBOX or KAFKA

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DlqStatus status = DlqStatus.PENDING;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime requeuedAt;

    public enum DlqStatus {
        PENDING, REQUEUED
    }
}
