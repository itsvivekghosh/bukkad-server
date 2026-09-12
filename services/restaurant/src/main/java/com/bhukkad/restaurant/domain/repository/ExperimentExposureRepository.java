package com.bhukkad.restaurant.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import com.bhukkad.restaurant.domain.entity.ExperimentExposure;

@Repository
public interface ExperimentExposureRepository extends JpaRepository<ExperimentExposure, Long> {

    Optional<ExperimentExposure> findByExperimentKeyAndUserId(String experimentKey, Long userId);

    @Query("SELECT e.variant, COUNT(e) FROM ExperimentExposure e " +
           "WHERE e.experimentKey = :experimentKey GROUP BY e.variant")
    List<Object[]> countByVariant(@Param("experimentKey") String experimentKey);
}
