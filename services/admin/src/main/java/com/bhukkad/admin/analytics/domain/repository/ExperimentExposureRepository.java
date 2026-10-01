package com.bhukkad.admin.analytics.domain.repository;
import com.bhukkad.admin.analytics.domain.entity.ExperimentExposure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ExperimentExposureRepository extends JpaRepository<ExperimentExposure, Long> {

    Optional<ExperimentExposure> findByExperimentKeyAndUserId(String experimentKey, Long userId);

    /** Cohort size per variant — the base of every lift calculation. */
    @Query("SELECT e.variant, COUNT(e) FROM ExperimentExposure e " +
           "WHERE e.experimentKey = :experimentKey GROUP BY e.variant")
    List<Object[]> countByVariant(@Param("experimentKey") String experimentKey);
}
