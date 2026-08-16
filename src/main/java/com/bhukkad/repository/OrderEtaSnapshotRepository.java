package com.bhukkad.repository;

import com.bhukkad.entity.OrderEtaSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface OrderEtaSnapshotRepository extends JpaRepository<OrderEtaSnapshot, Long> {
    List<OrderEtaSnapshot> findByOrderIdOrderByRecordedAtDesc(Long orderId);

    long countByRecordedAtAfter(LocalDateTime since);

    @Query("SELECT COALESCE(AVG(s.etaMinutes), 0) FROM OrderEtaSnapshot s WHERE s.recordedAt >= :since")
    Double avgEtaMinutesSince(@Param("since") LocalDateTime since);
}
