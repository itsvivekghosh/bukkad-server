package com.bhukkad.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SagaStepRepository extends JpaRepository<SagaStep, Long> {

    Optional<SagaStep> findBySagaInstanceIdAndStepOrder(Long sagaInstanceId, int stepOrder);
}
