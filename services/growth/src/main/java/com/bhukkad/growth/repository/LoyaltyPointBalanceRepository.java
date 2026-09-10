package com.bhukkad.growth.repository;

import com.bhukkad.growth.entity.LoyaltyPointBalance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LoyaltyPointBalanceRepository extends JpaRepository<LoyaltyPointBalance, Long> {

    Optional<LoyaltyPointBalance> findByCustomerId(Long customerId);

    /**
     * Atomic credit: a single upsert that appends to the existing row without
     * a Java read-modify-write (a lost update here is real money).
     */
    @Modifying
    @Query(value = """
            INSERT INTO loyalty_point_balances (customer_id, points, lifetime_points, updated_at)
            VALUES (:customerId, :points, :points, CURRENT_TIMESTAMP)
            ON CONFLICT (customer_id) DO UPDATE SET
                points = loyalty_point_balances.points + EXCLUDED.points,
                lifetime_points = loyalty_point_balances.lifetime_points + EXCLUDED.points,
                updated_at = CURRENT_TIMESTAMP
            """, nativeQuery = true)
    int credit(@Param("customerId") Long customerId, @Param("points") int points);

    /**
     * Conditional single-statement decrement (ADR-005): 0 updated rows means
     * the balance did not cover the redemption — never a TOCTOU check-then-
     * decrement, so concurrent redemptions can never overspend.
     */
    @Modifying
    @Query(value = """
            UPDATE loyalty_point_balances
            SET points = points - :points, updated_at = CURRENT_TIMESTAMP
            WHERE customer_id = :customerId AND points >= :points
            """, nativeQuery = true)
    int redeem(@Param("customerId") Long customerId, @Param("points") int points);

    /** Materializes a missing balance row from the ledger sums (idempotent). */
    @Modifying
    @Query(value = """
            INSERT INTO loyalty_point_balances (customer_id, points, lifetime_points, updated_at)
            SELECT :customerId,
                   COALESCE(SUM(CASE WHEN transaction_type = 'CREDIT' THEN points ELSE -points END), 0),
                   COALESCE(SUM(CASE WHEN transaction_type = 'CREDIT' THEN points ELSE 0 END), 0),
                   CURRENT_TIMESTAMP
            FROM loyalty_points_ledger
            WHERE customer_id = :customerId
            ON CONFLICT (customer_id) DO NOTHING
            """, nativeQuery = true)
    int materializeFromLedger(@Param("customerId") Long customerId);

    /**
     * Reconciliation (ADR-005): one statement that snaps every drifted balance
     * row back to the authoritative ledger sum and returns how many rows were
     * corrected. Rows already matching are untouched.
     */
    @Modifying
    @Query(value = """
            UPDATE loyalty_point_balances b
            SET points = agg.balance, lifetime_points = agg.lifetime, updated_at = CURRENT_TIMESTAMP
            FROM (
                SELECT customer_id,
                       COALESCE(SUM(CASE WHEN transaction_type = 'CREDIT' THEN points ELSE -points END), 0) AS balance,
                       COALESCE(SUM(CASE WHEN transaction_type = 'CREDIT' THEN points ELSE 0 END), 0) AS lifetime
                FROM loyalty_points_ledger
                GROUP BY customer_id
            ) agg
            WHERE b.customer_id = agg.customer_id
              AND (b.points <> agg.balance OR b.lifetime_points <> agg.lifetime)
            """, nativeQuery = true)
    int reconcileAllFromLedger();

    /** Drift report rows for the reconciliation metric/alert. */
    @Query(value = """
            SELECT b.customer_id AS "customerId",
                   b.points - agg.balance AS "drift"
            FROM loyalty_point_balances b
            JOIN (
                SELECT customer_id,
                       COALESCE(SUM(CASE WHEN transaction_type = 'CREDIT' THEN points ELSE -points END), 0) AS balance
                FROM loyalty_points_ledger
                GROUP BY customer_id
            ) agg ON agg.customer_id = b.customer_id
            WHERE b.points <> agg.balance
            """, nativeQuery = true)
    List<DriftProjection> findDriftedBalances();

    interface DriftProjection {
        Long getCustomerId();

        long getDrift();
    }
}
