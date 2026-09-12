package com.bhukkad.order.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import com.bhukkad.order.domain.entity.OrderEtaSnapshot;

public interface OrderEtaSnapshotRepository extends JpaRepository<OrderEtaSnapshot, Long> {
    List<OrderEtaSnapshot> findByOrderId(Long orderId);
}