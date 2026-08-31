package com.bhukkad.payment.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WalletBalanceRepository extends JpaRepository<WalletBalance, Long> {
    Optional<WalletBalance> findByCustomerId(Long customerId);
}