package com.bhukkad.engagement.support.chat.domain.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * FraudAlert records a fraud detection event on a chat room or message.
 *
 * <p>Each alert captures the detection reason, severity, score, and whether
 * it has been reviewed by an admin. Alerts feed into the admin dashboard
 * for manual review and potential account actions.</p>
 *
 * <p>Alert severity levels:</p>
 * <ul>
 *   <li>LOW: Minor anomaly (e.g., slight message velocity increase)</li>
 *   <li>MEDIUM: Moderate risk (e.g., duplicate image, missing EXIF data)</li>
 *   <li>HIGH: Significant risk (e.g., REFUND scam keywords, tampered image)</li>
 *   <li>CRITICAL: Immediate action required (e.g., multiple confirmed fraud indicators)</li>
 * </ul>
 */
@Entity
@Table(name = "fraud_alerts", indexes = {
    @Index(name = "idx_fraud_alert_room", columnList = "room_id"),
    @Index(name = "idx_fraud_alert_message", columnList = "message_id"),
    @Index(name = "idx_fraud_alert_severity", columnList = "severity"),
    @Index(name = "idx_fraud_alert_reviewed", columnList = "reviewed"),
    @Index(name = "idx_fraud_alert_created", columnList = "created_at DESC"),
    @Index(name = "idx_fraud_alert_score", columnList = "risk_score DESC")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FraudAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", unique = true, nullable = false, updatable = false)
    @JdbcTypeCode(SqlTypes.UUID)
    private UUID publicId;

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Column(name = "message_id")
    private Long messageId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "alert_type", length = 50, nullable = false)
    private String alertType; // DUPLICATE_MESSAGE, IMAGE_TAMPERING, REFUND_KEYWORD, VELOCITY, ORDER_MISMATCH, MULTI_ACCOUNT

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", length = 20, nullable = false)
    private Severity severity;

    @Column(name = "risk_score", nullable = false)
    private Integer riskScore;

    @Column(name = "description", length = 1000, nullable = false)
    private String description;

    @Column(name = "evidence", length = 4000)
    private String evidence;

    @Column(name = "reviewed", nullable = false)
    @Builder.Default
    private Boolean reviewed = false;

    @Column(name = "reviewed_by")
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "review_note", length = 1000)
    private String reviewNote;

    @Column(name = "action_taken", length = 200)
    private String actionTaken;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (publicId == null) {
            publicId = java.util.UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = java.time.LocalDateTime.now();
        }
        if (reviewed == null) {
            reviewed = false;
        }
    }

    public enum Severity {
        LOW, MEDIUM, HIGH, CRITICAL
    }
}
