package com.bhukkad.compliance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface DataExportRequestRepository extends JpaRepository<DataExportRequest, Long> {

    Optional<DataExportRequest> findByIdAndUserId(Long id, Long userId);

    List<DataExportRequest> findByUserId(Long userId);

    Optional<DataExportRequest> findTopByUserIdAndStatusOrderByCompletedAtDesc(
            Long userId, DataExportRequest.Status status);

    int deleteByRequestedAtBefore(LocalDateTime cutoff);
}
