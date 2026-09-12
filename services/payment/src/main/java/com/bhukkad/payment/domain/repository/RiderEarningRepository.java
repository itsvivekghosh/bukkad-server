package com.bhukkad.payment.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.bhukkad.payment.domain.entity.RiderEarning;
import java.util.List;

@Repository
public interface RiderEarningRepository extends JpaRepository<RiderEarning, Long> {

    List<RiderEarning> findByAgentId(Long agentId);

    List<RiderEarning> findByAgentIdAndStatus(Long agentId, String status);

    /**
     * Earnings dedup: a delivery earns exactly once. Count instead of a
     * unique constraint so a replay of {@code record} can be detected before
     * insert without a migration on legacy tables.
     */
    @Query("SELECT COUNT(e) FROM RiderEarning e WHERE e.agentId = :agentId AND e.orderId = :orderId AND e.status <> 'CANCELLED'")
    long countByAgentIdAndOrderId(@Param("agentId") Long agentId, @Param("orderId") Long orderId);

    /**
     * Guarded state transition: only an EARNED row may flip to PAID, so a
     * replay or double-payout request is a no-op (0 rows).
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE RiderEarning e SET e.status = 'PAID', e.paidAt = CURRENT_TIMESTAMP " +
            "WHERE e.id = :earningId AND e.status = 'EARNED'")
    int markPaidIfEarned(@Param("earningId") Long earningId);
}
