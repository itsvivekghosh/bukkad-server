package com.bhukkad.churn;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * Latest churn score for one customer, recomputed weekly by
 * {@link ChurnPredictionService}. One row per customer (upsert semantics) so the
 * admin dashboard reads are trivial.
 */
@Entity
@Table(name = "churn_scores", indexes = {
        @Index(name = "idx_churn_score", columnList = "score"),
        @Index(name = "idx_churn_risk_scored", columnList = "risk_level, scored_at")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class ChurnScore {

    public enum RiskLevel { LOW, MEDIUM, HIGH }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    /** 0–100; higher means more likely to churn. */
    @Column(nullable = false)
    private int score;

    @Column(name = "risk_level", nullable = false, length = 10)
    private RiskLevel riskLevel;

    /** Human-readable factor list, e.g. {@code days_inactive=42|orders_declining}. */
    @Column(columnDefinition = "TEXT")
    private String factors;

    @LastModifiedDate
    @Column(name = "scored_at", nullable = false)
    private LocalDateTime scoredAt;

    /** True once a retention action has been dispatched for this score round. */
    @Column(name = "retention_action_taken", nullable = false)
    private boolean retentionActionTaken = false;
}
