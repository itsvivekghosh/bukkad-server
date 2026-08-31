package com.bhukkad.delivery.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AgentCodWalletRepository extends JpaRepository<AgentCodWallet, Long> {
    Optional<AgentCodWallet> findByAgentId(Long agentId);
}