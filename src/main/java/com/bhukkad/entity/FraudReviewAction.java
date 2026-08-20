package com.bhukkad.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * One manual review decision on a fraud event (Fraud Dashboard review queue).
 *
 * <p>{@code FraudEvent} rows are append-only evidence; this table is the separate
 * state machine where an admin decides what to do about a suspicious event.
 * Each fraud event has at most one review decision ({@code uk_fraud_review_event}).</p>
 */
@Entity
@Table(name = "fraud_review_queue", indexes = {
        @Index(name = "uk_fraud_review_event", columnList = "fraud_event_id", unique = true),
        @Index(name = "idx_fraud_review_status", columnList = "status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class FraudReviewAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fraud_event_id", nullable = false, unique = true)
    private FraudEvent fraudEvent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private FraudReviewActionType action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FraudReviewStatus status = FraudReviewStatus.PENDING;

    @Column(length = 500)
    private String notes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private User reviewedBy;

    private LocalDateTime reviewedAt;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public enum FraudReviewActionType {
        BLOCK_CUSTOMER,
        IGNORE
    }

    public enum FraudReviewStatus {
        PENDING,
        DONE
    }
}
