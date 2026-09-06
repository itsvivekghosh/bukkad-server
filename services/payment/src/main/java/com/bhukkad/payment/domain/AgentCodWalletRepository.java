package com.bhukkad.payment.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AgentCodWalletRepository extends JpaRepository<AgentCodWallet, Long> {

    Optional<AgentCodWallet> findByAgentId(Long agentId);
}
