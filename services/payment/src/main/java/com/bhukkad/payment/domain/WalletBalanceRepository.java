package com.bhukkad.payment.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface WalletBalanceRepository extends JpaRepository<WalletBalance, Long> {

    /**
     * Pessimistic read used by money mutations: serializes concurrent
     * credit/debit on the same wallet so a read-modify-write cannot lose
     * updates or overdraw.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM WalletBalance w WHERE w.customerId = :customerId")
    Optional<WalletBalance> findByCustomerIdForUpdate(@Param("customerId") Long customerId);

    Optional<WalletBalance> findByCustomerId(Long customerId);
}
