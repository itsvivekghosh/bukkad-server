package com.bhukkad.engagement.support.chat.domain.repository;

import com.bhukkad.engagement.support.chat.domain.entity.FraudAlert;
import com.bhukkad.engagement.support.chat.domain.entity.FraudAlert.Severity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for FraudAlert entities.
 */
@Repository
public interface FraudAlertRepository extends JpaRepository<FraudAlert, Long> {

    Optional<FraudAlert> findByPublicId(UUID publicId);

    List<FraudAlert> findByRoomId(Long roomId);

    List<FraudAlert> findByMessageId(Long messageId);

    List<FraudAlert> findByCustomerId(Long customerId);

    List<FraudAlert> findBySeverity(Severity severity);

    List<FraudAlert> findByReviewed(Boolean reviewed);

    List<FraudAlert> findBySeverityAndReviewed(Severity severity, Boolean reviewed);

    @Query("SELECT f FROM FraudAlert f WHERE f.severity = :severity AND f.reviewed = false ORDER BY f.riskScore DESC, f.createdAt ASC")
    List<FraudAlert> findUnreviewedBySeverity(@Param("severity") Severity severity);

    @Query("SELECT f FROM FraudAlert f WHERE f.customerId = :customerId ORDER BY f.createdAt DESC")
    List<FraudAlert> findByCustomerIdOrderByCreatedDesc(@Param("customerId") Long customerId);

    @Query("SELECT COUNT(f) FROM FraudAlert f WHERE f.reviewed = false")
    Long countUnreviewed();

    @Query("SELECT COUNT(f) FROM FraudAlert f WHERE f.severity = :severity AND f.reviewed = false")
    Long countUnreviewedBySeverity(@Param("severity") Severity severity);

    @Query("SELECT COUNT(f) FROM FraudAlert f WHERE f.createdAt >= :since")
    Long countByCreatedSince(@Param("since") LocalDateTime since);

    @Query("SELECT f FROM FraudAlert f WHERE f.createdAt >= :since ORDER BY f.createdAt DESC")
    List<FraudAlert> findByCreatedSince(@Param("since") LocalDateTime since);

    void deleteByRoomId(Long roomId);
}
