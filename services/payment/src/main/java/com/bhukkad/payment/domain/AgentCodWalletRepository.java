package com.bhukkad.payment.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AgentCodWalletRepository extends JpaRepository<AgentCodWallet, Long> {

    /**
     * Pessimistic read for COD money mutations: serializes concurrent
     * credit/debit on the same rider wallet (check-then-act could previously
     * overdraw or lose credits).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM AgentCodWallet w WHERE w.agentId = :agentId")
    Optional<AgentCodWallet> findByAgentIdForUpdate(@Param("agentId") Long agentId);

    Optional<AgentCodWallet> findByAgentId(Long agentId);
}
