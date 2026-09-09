package com.bhukkad.order.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrderDeliveryProofRepository extends JpaRepository<OrderDeliveryProof, Long> {
    Optional<OrderDeliveryProof> findByOrderId(Long orderId);
}
