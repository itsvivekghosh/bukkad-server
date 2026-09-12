package com.bhukkad.growth.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Append-only loyalty ledger row (ADR-005): the durable source of truth for
 * loyalty balances. Every CREDIT or DEBIT is recorded here exactly once;
 * {@code loyalty_point_balances} is only an O(1) read model re-derivable as
 * {@code SUM(CREDIT) - SUM(DEBIT)} over this table.
 *
 * <p>Backed by the V1 baseline table (previously had no entity/repo at all —
 * a Redis flush silently destroyed all balances).</p>
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "loyalty_points_ledger")
public class LoyaltyPointsLedger {

    public static final String TYPE_CREDIT = "CREDIT";
    public static final String TYPE_DEBIT = "DEBIT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(nullable = false)
    private int points;

    @Column(name = "transaction_type", nullable = false, length = 20)
    private String transactionType;

    @Column(length = 100)
    private String reason;

    /** Idempotency/dedup reference (e.g. the LOYALTY_CREDIT idempotency key). */
    @Column(name = "reference_id", length = 50)
    private String referenceId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public static LoyaltyPointsLedger credit(Long customerId, int points, String reason, String referenceId) {
        return of(customerId, points, TYPE_CREDIT, reason, referenceId);
    }

    public static LoyaltyPointsLedger debit(Long customerId, int points, String reason, String referenceId) {
        return of(customerId, points, TYPE_DEBIT, reason, referenceId);
    }

    private static LoyaltyPointsLedger of(Long customerId, int points, String type, String reason, String referenceId) {
        LoyaltyPointsLedger row = new LoyaltyPointsLedger();
        row.setCustomerId(customerId);
        row.setPoints(points);
        row.setTransactionType(type);
        row.setReason(reason);
        row.setReferenceId(referenceId);
        return row;
    }

    @PrePersist
    void stampCreatedAt() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
