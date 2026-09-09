package com.bhukkad.order.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderEtaSnapshotRepository extends JpaRepository<OrderEtaSnapshot, Long> {
    List<OrderEtaSnapshot> findByOrderId(Long orderId);
}