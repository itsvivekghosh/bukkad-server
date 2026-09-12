package com.bhukkad.admin.domain.repository;
import com.bhukkad.admin.domain.entity.ChurnScore;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChurnScoreRepository extends JpaRepository<ChurnScore, Long> {
    List<ChurnScore> findByCustomerId(Long customerId);

    /** Latest scoring round for one customer (upsert target for on-demand rescore). */
    Optional<ChurnScore> findFirstByCustomerIdOrderByComputedAtDesc(Long customerId);

    /**
     * High-risk cohort for the admin retention dashboard. The service schema has
     * no persisted risk level, so the monolith's {@code riskLevel = 'HIGH'} filter
     * is expressed as the retention-threshold band on the 0–1 score.
     */
    List<ChurnScore> findTop100ByScoreGreaterThanEqualOrderByScoreDesc(double score);
}