package com.bhukkad.growth.repository;

import com.bhukkad.growth.entity.LoyaltyPointsLedger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface LoyaltyPointsLedgerRepository extends JpaRepository<LoyaltyPointsLedger, Long> {

    boolean existsByReferenceId(String referenceId);

    /**
     * Ledger sum = source of truth for a customer's current balance
     * (CREDIT minus DEBIT rows). Used by the nightly reconciliation job and
     * the cache rebuild-on-miss path.
     */
    @Query("""
            SELECT COALESCE(SUM(CASE WHEN l.transactionType = 'CREDIT' THEN l.points ELSE -l.points END), 0)
            FROM LoyaltyPointsLedger l
            WHERE l.customerId = :customerId
            """)
    long ledgerBalance(@Param("customerId") Long customerId);

    /** Lifetime earned = every CREDIT row (tiers/progress never decrease). */
    @Query("SELECT COALESCE(SUM(l.points), 0) FROM LoyaltyPointsLedger l "
            + "WHERE l.customerId = :customerId AND l.transactionType = 'CREDIT'")
    long ledgerLifetime(@Param("customerId") Long customerId);

    /** Per-customer daily credit cap ceiling: today's credited points. */
    @Query("SELECT COALESCE(SUM(l.points), 0) FROM LoyaltyPointsLedger l "
            + "WHERE l.customerId = :customerId AND l.transactionType = 'CREDIT' "
            + "AND l.createdAt >= :dayStart")
    long creditedSince(@Param("customerId") Long customerId, @Param("dayStart") LocalDateTime dayStart);

    /** All customers that have ledger activity (reconciliation iteration). */
    @Query("SELECT DISTINCT l.customerId FROM LoyaltyPointsLedger l")
    List<Long> findDistinctCustomerIds();
}
