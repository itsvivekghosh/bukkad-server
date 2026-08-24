package com.bhukkad.repository;

import com.bhukkad.entity.AgentCodWallet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AgentCodWalletRepository extends JpaRepository<AgentCodWallet, Long> {

    Optional<AgentCodWallet> findByAgentId(Long agentId);

    /** Returns the wallet for the agent, creating an unsaved empty one on first use. */
    default AgentCodWallet getOrCreateByAgentId(Long agentId) {
        return findByAgentId(agentId).orElseGet(() -> {
            AgentCodWallet wallet = new AgentCodWallet();
            wallet.setAgentId(agentId);
            return wallet;
        });
    }
}
