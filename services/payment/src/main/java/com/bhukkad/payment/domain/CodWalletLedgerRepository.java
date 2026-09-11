package com.bhukkad.payment.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistence surface for {@link CodWalletLedger}. The ledger is append-only:
 * nothing in the application reads it inside the money path, so no query
 * methods are declared yet (audit reads go through ops SQL).
 */
@Repository
public interface CodWalletLedgerRepository extends JpaRepository<CodWalletLedger, Long> {
}
