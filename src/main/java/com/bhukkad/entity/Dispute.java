package com.bhukkad.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "disputes", indexes = {
        @Index(name = "idx_dispute_order", columnList = "order_id"),
        @Index(name = "idx_dispute_status", columnList = "status"),
        @Index(name = "idx_dispute_created", columnList = "createdAt")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class Dispute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DisputeType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DisputeStatus status;

    @Column(columnDefinition = "TEXT")
    private String customerEvidence;

    @Column(columnDefinition = "TEXT")
    private String riderEvidence;

    @Column(columnDefinition = "TEXT")
    private String restaurantEvidence;

    @Column(columnDefinition = "TEXT")
    private String resolutionNotes;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private DisputeResolution resolution;

    private Double refundAmount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_by")
    private User resolvedBy;

    private LocalDateTime resolvedAt;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public enum DisputeType {
        ORDER_NOT_RECEIVED,
        WRONG_ORDER,
        LATE_DELIVERY,
        FOOD_QUALITY,
        PAYMENT_ISSUE,
        OTHER
    }

    public enum DisputeStatus {
        OPEN,
        UNDER_REVIEW,
        AUTO_RESOLVED,
        MANUAL_RESOLVED,
        CLOSED
    }

    public enum DisputeResolution {
        FULL_REFUND,
        PARTIAL_REFUND,
        NO_REFUND,
        CREDIT_ISSUED,
        ESCALATED
    }
}