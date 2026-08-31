package com.bhukkad.wallet;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.QueryHints;

import java.util.Optional;

public interface WalletBalanceRepository extends JpaRepository<WalletBalance, Long> {

    Optional<WalletBalance> findByCustomerId(Long customerId);

    /**
     * Pessimistic write lock on the wallet's OWN row: concurrent debits and
     * credits for one customer are serialised here. 3s lock timeout keeps a
     * stuck holder from stalling the payment path indefinitely.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    Optional<WalletBalance> findWithLockByCustomerId(Long customerId);
}
