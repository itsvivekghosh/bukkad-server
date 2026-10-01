package com.bhukkad.admin.analytics.domain.repository;
import com.bhukkad.admin.analytics.domain.entity.DataExportRequest;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DataExportRequestRepository extends JpaRepository<DataExportRequest, Long> {
    List<DataExportRequest> findByCustomerId(Long customerId);
    long deleteByCreatedAtBefore(java.time.LocalDateTime cutoff);
}
