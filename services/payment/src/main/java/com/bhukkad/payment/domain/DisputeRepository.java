package com.bhukkad.payment.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DisputeRepository extends JpaRepository<Dispute, Long> {
    List<Dispute> findByCustomerId(Long customerId);
    List<Dispute> findByStatus(String status);
}