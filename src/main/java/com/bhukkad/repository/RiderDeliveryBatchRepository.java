package com.bhukkad.repository;

import com.bhukkad.entity.RiderDeliveryBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RiderDeliveryBatchRepository extends JpaRepository<RiderDeliveryBatch, Long> {
    List<RiderDeliveryBatch> findByAgentIdAndStatusOrderByCreatedAtDesc(
            Long agentId, RiderDeliveryBatch.BatchStatus status);

    Optional<RiderDeliveryBatch> findFirstByAgentIdAndStatusOrderByCreatedAtDesc(
            Long agentId, RiderDeliveryBatch.BatchStatus status);
}
