package com.bhukkad.identity.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer, Long> {
    Optional<Customer> findByEmail(String email);
    Optional<Customer> findByEmailAndIsActiveTrue(String email);
    Optional<Customer> findByReferralCode(String referralCode);
    long countByReferredById(Long referredById);
}