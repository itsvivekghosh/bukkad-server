package com.bhukkad.admin.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExperimentExposureRepository extends JpaRepository<ExperimentExposure, Long> {
    Optional<ExperimentExposure> findByCustomerIdAndExperiment(Long customerId, String experiment);
    List<ExperimentExposure> findByCustomerId(Long customerId);
}