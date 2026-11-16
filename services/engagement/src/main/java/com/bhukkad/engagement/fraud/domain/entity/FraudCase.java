package com.bhukkad.engagement.fraud.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Fraud case entity for recording detected fraudulent activities.
 * Tracks fake order claims, refund scams, and identity spoofing attempts.
 */
@Entity
@Table(name = "fraud_cases",
       indexes = {
           @Index(name = "idx_fraud_cases_session_id", columnList = "session_id"),
           @Index(name = "idx_fraud_cases_status", columnList = "status"),
           @Index(name = "idx_fraud_cases_fraud_type", columnList = "fraud_type"),
           @Index(name = "idx_fraud_cases_risk_score", columnList = "fraud_risk_score DESC"),
           @Index(name = "idx_fraud_cases_created_at", columnList = "created_at DESC")
       })
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PACKAGE)
@Builder
public class FraudCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_id", nullable = false, unique = true)
    private UUID caseId;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "message_id")
    private UUID messageId;

    @Column(name = "attachment_id")
    private UUID attachmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "fraud_type", nullable = false, length = 50)
    private FraudType fraudType;

    @Column(name = "risk_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal riskScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "confidence_level", nullable = false, length = 20)
    private ConfidenceLevel confidenceLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "detection_method", nullable = false, length = 100)
    private String detectionMethod;

    @Column(name = "flags_found", columnDefinition = "JSONB")
    private String flagsFound; // JSON string

    @Column(name = "details", columnDefinition = "JSONB")
    private String details; // JSON string

    @Column(name = "assigned_to")
    private Long assignedTo;

    @Column(name = "resolution_notes", columnDefinition = "TEXT")
    private String resolutionNotes;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    public enum FraudType {
        FAKE_ORDER_CLAIM("Claiming non-existent or different order"),
        REFUND_SCAM("Attempting fraudulent refund claims"),
        IDENTITY_SPOOFING("Impersonating another customer or agent"),
        ATTACHMENT_MANIPULATION("Tampered or synthetic evidence images"),
        BEHAVIORAL_ANOMALY("Unusual customer behavior patterns");

        private final String description;

        FraudType(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    public enum ConfidenceLevel {
        LOW(0.3),
        MEDIUM(0.5),
        HIGH(0.7),
        CRITICAL(0.9);

        private final double threshold;

        ConfidenceLevel(double threshold) {
            this.threshold = threshold;
        }

        public double getThreshold() {
            return threshold;
        }
    }

    public enum Status {
        DETECTED("Fraud detected, awaiting review"),
        REVIEWED("Reviewed by agent, awaiting action"),
        CONFIRMED("Confirmed as fraudulent"),
        DISMISSED("False positive, dismissed"),
        ACTION_TAKEN("Action taken (refund blocked, account flagged)");

        private final String description;

        Status(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
        if (caseId == null) {
            caseId = UUID.randomUUID();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}