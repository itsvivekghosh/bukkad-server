package com.bhukkad.common.saga;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SagaInstanceRepository extends JpaRepository<SagaInstance, Long> {
    SagaInstance findBySagaId(String sagaId);
}
