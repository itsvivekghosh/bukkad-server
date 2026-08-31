package com.bhukkad.identity.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CustomerMembershipRepository extends JpaRepository<CustomerMembership, Long> {
    Optional<CustomerMembership> findFirstByCustomerIdAndStatusOrderByExpiresAtDesc(Long customerId, String status);
}