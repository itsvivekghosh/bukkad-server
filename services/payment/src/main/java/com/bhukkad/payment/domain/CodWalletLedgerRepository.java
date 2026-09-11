package com.bhukkad.payment.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CodWalletLedgerRepository extends JpaRepository<CodWalletLedger, Long> {

    List<CodWalletLedger> findByAgentIdOrderByIdAsc(Long agentId);
}
