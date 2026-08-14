package com.bhukkad.repository;

import com.bhukkad.entity.RiderEarning;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface RiderEarningRepository extends JpaRepository<RiderEarning, Long> {
    boolean existsByOrderId(Long orderId);

    Page<RiderEarning> findByAgentIdOrderByCreatedAtDesc(Long agentId, Pageable pageable);

    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM RiderEarning e WHERE e.agent.id = :agentId AND e.status = :status")
    Double sumAmountByAgentIdAndStatus(@Param("agentId") Long agentId, @Param("status") RiderEarning.EarningStatus status);
}
