package com.bhukkad.admin.domain.repository;
import com.bhukkad.admin.domain.entity.FraudEvent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import org.springframework.data.domain.Pageable;

public interface FraudEventRepository extends JpaRepository<FraudEvent, Long> {
    List<FraudEvent> findByCustomerId(Long customerId);
    List<FraudEvent> findByStatus(String status);

    /** PERF-3: bounded, newest-first page of one status (SQL ORDER BY). */
    List<FraudEvent> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);
    long deleteByCreatedAtBefore(java.time.LocalDateTime cutoff);
}