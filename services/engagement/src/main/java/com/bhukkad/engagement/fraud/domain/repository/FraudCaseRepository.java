package com.bhukkad.engagement.fraud.domain.repository;

import com.bhukkad.engagement.fraud.domain.entity.FraudCase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Repository for FraudCase entities.
 */
public interface FraudCaseRepository extends JpaRepository<FraudCase, Long> {

    Optional<FraudCase> findByCaseId(UUID caseId);

    List<FraudCase> findBySessionIdOrderByCreatedAtDesc(UUID sessionId);

    List<FraudCase> findByStatusOrderByCreatedAtDesc(FraudCase.Status status);

    @Query("SELECT f FROM FraudCase f WHERE f.fraudType = :fraudType AND f.createdAt >= :since ORDER BY f.riskScore DESC")
    List<FraudCase> findByFraudTypeSince(@Param("fraudType") FraudCase.FraudType fraudType, 
                                          @Param("since") LocalDateTime since);

    @Query("SELECT f FROM FraudCase f WHERE f.riskScore >= :minScore ORDER BY f.riskScore DESC")
    List<FraudCase> findHighRiskCases(@Param("minScore") double minScore);

    default List<FraudCase> findHighRiskCases(double minScore, FraudCase.ConfidenceLevel... confidences) {
        List<FraudCase> allHighRisk = findHighRiskCases(minScore);
        if (confidences == null || confidences.length == 0) {
            return allHighRisk;
        }
        Set<FraudCase.ConfidenceLevel> allowed = Set.of(confidences);
        return allHighRisk.stream()
                .filter(c -> allowed.contains(c.getConfidenceLevel()))
                .toList();
    }

    Page<FraudCase> findByStatusOrderByCreatedAtDesc(FraudCase.Status status, Pageable pageable);

    List<FraudCase> findByAssignedToAndStatusOrderByCreatedAtDesc(Long assignedTo, FraudCase.Status status);

    @Query("SELECT f FROM FraudCase f WHERE f.createdAt >= :since AND f.status = 'DETECTED'")
    List<FraudCase> findNewCasesSince(@Param("since") LocalDateTime since);

    long countByStatus(FraudCase.Status status);

    long countByFraudType(FraudCase.FraudType fraudType);

    @Query("SELECT COUNT(f) FROM FraudCase f WHERE f.createdAt >= :since")
    long countNewCasesSince(@Param("since") LocalDateTime since);
}