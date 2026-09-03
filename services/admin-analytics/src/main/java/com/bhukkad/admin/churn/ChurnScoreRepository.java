package com.bhukkad.admin.churn;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChurnScoreRepository extends JpaRepository<ChurnScore, Long> {

    /**
     * Hibernate 6.3.1 throws ArrayIndexOutOfBoundsException while constructing
     * the SQM plan for HQL {@code IN} lists; the native query avoids the
     * defect entirely.
     */
    @Query(value = "SELECT * FROM churn_scores WHERE user_id IN (:userIds)", nativeQuery = true)
    List<ChurnScore> findByUserIdIn(@Param("userIds") List<Long> userIds);

    /** High-risk customers for the admin retention dashboard. */
    List<ChurnScore> findTop100ByRiskLevelOrderByScoreDesc(ChurnScore.RiskLevel riskLevel);
}
