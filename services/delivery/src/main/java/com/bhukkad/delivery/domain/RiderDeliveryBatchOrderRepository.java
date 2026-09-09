package com.bhukkad.delivery.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RiderDeliveryBatchOrderRepository extends JpaRepository<RiderDeliveryBatchOrder, Long> {
    List<RiderDeliveryBatchOrder> findByBatchId(Long batchId);
}