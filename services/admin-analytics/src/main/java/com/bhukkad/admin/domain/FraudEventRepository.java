package com.bhukkad.admin.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FraudEventRepository extends JpaRepository<FraudEvent, Long> {
    List<FraudEvent> findByCustomerId(Long customerId);
    List<FraudEvent> findByStatus(String status);
    long deleteByCreatedAtBefore(java.time.LocalDateTime cutoff);
}