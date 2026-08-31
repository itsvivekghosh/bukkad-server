package com.bhukkad.delivery.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DeliveryAssignmentRepository extends JpaRepository<DeliveryAssignment, Long> {
    Optional<DeliveryAssignment> findByOrderId(Long orderId);
}