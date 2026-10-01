package com.bhukkad.commerce.delivery.domain.repository;
import com.bhukkad.commerce.delivery.domain.entity.RiderDeliveryBatchOrder;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RiderDeliveryBatchOrderRepository extends JpaRepository<RiderDeliveryBatchOrder, Long> {
    List<RiderDeliveryBatchOrder> findByBatchId(Long batchId);
}