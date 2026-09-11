package com.bhukkad.payment.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Append-only persistence for {@link CodWalletLedger} rows (audit V-02).
 * Deliberately exposes no delete/update surface — the ledger is evidence.
 */
@Repository
public interface CodWalletLedgerRepository extends JpaRepository<CodWalletLedger, Long> {
}
