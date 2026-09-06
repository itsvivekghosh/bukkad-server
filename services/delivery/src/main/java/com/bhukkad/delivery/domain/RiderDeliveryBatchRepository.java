package com.bhukkad.delivery.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RiderDeliveryBatchRepository extends JpaRepository<RiderDeliveryBatch, Long> {
    List<RiderDeliveryBatch> findByAgentIdAndStatus(Long agentId, String status);

    List<RiderDeliveryBatch> findByAgentId(Long agentId);
}