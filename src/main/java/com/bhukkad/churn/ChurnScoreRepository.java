package com.bhukkad.churn;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface ChurnScoreRepository extends JpaRepository<ChurnScore, Long> {

    List<ChurnScore> findByUserIdIn(Collection<Long> userIds);

    /** High-risk customers for the admin retention dashboard. */
    List<ChurnScore> findTop100ByRiskLevelOrderByScoreDesc(ChurnScore.RiskLevel riskLevel);
}
