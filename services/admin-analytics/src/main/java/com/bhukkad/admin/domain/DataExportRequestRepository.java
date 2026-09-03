package com.bhukkad.admin.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DataExportRequestRepository extends JpaRepository<DataExportRequest, Long> {
    List<DataExportRequest> findByCustomerId(Long customerId);
    long deleteByCreatedAtBefore(java.time.LocalDateTime cutoff);
}
