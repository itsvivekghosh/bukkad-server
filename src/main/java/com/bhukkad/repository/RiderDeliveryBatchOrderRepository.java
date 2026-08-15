package com.bhukkad.repository;

import com.bhukkad.entity.RiderDeliveryBatchOrder;
import com.bhukkad.entity.RiderDeliveryBatchOrderId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RiderDeliveryBatchOrderRepository extends JpaRepository<RiderDeliveryBatchOrder, RiderDeliveryBatchOrderId> {
    List<RiderDeliveryBatchOrder> findByBatchIdOrderBySequenceNumberAsc(Long batchId);

    boolean existsByOrderId(Long orderId);
}
