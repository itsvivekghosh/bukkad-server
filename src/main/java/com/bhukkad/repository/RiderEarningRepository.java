package com.bhukkad.repository;

import com.bhukkad.entity.RiderEarning;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface RiderEarningRepository extends JpaRepository<RiderEarning, Long> {
    boolean existsByOrderId(Long orderId);

    Page<RiderEarning> findByAgentIdOrderByCreatedAtDesc(Long agentId, Pageable pageable);

    /**
     * Cursor-paginated rider payout history. Keyset predicate on
     * {@code (createdAt, id)} mirrors the pattern used for orders, wallet
     * transactions and settlements so all cursor APIs behave identically.
     */
    @Query("""
            SELECT e FROM RiderEarning e
            WHERE e.agent.id = :agentId
            AND (:cursorCreatedAt IS NULL OR e.createdAt < :cursorCreatedAt
                 OR (e.createdAt = :cursorCreatedAt AND e.id < :cursorId))
            ORDER BY e.createdAt DESC, e.id DESC
            """)
    List<RiderEarning> findByAgentIdAfterCursor(
            @Param("agentId") Long agentId,
            @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
            @Param("cursorId") Long cursorId,
            Pageable pageable);

    List<RiderEarning> findByAgentIdAndStatus(Long agentId, RiderEarning.EarningStatus status);

    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM RiderEarning e WHERE e.agent.id = :agentId AND e.status = :status")
    Double sumAmountByAgentIdAndStatus(@Param("agentId") Long agentId, @Param("status") RiderEarning.EarningStatus status);

    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM RiderEarning e WHERE e.status = :status")
    Double sumAmountByStatus(@Param("status") RiderEarning.EarningStatus status);

    @Query("SELECT COUNT(e) FROM RiderEarning e WHERE e.status = :status")
    long countByStatus(@Param("status") RiderEarning.EarningStatus status);
}
