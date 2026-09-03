package com.bhukkad.order.domain;

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

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Evidence-based dispute resolution extracted from the monolith
 * (com.bhukkad.entity.Dispute + com.bhukkad.support.DisputeResolutionService).
 *
 * <p>A customer files a dispute against an order. The service attempts
 * auto-resolution based on evidence; failing that, the dispute goes to an admin
 * manual queue.</p>
 */
@Entity
@Table(name = "disputes", indexes = {
        @Index(name = "idx_dispute_status", columnList = "status"),
        @Index(name = "idx_dispute_created", columnList = "createdAt")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
public class Dispute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, unique = true)
    private Long orderId;

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

    @Column(precision = 12, scale = 2)
    private BigDecimal refundAmount;

    @Column(name = "resolved_by")
    private Long resolvedBy;

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