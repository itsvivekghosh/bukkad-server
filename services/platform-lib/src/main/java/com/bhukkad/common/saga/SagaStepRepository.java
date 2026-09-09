package com.bhukkad.common.saga;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SagaStepRepository extends JpaRepository<SagaStep, Long> {
    Optional<SagaStep> findBySagaInstanceIdAndStepOrder(Long sagaInstanceId, int stepOrder);
}
