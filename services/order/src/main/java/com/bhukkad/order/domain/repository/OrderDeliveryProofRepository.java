package com.bhukkad.order.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import com.bhukkad.order.domain.entity.OrderDeliveryProof;

public interface OrderDeliveryProofRepository extends JpaRepository<OrderDeliveryProof, Long> {
    Optional<OrderDeliveryProof> findByOrderId(Long orderId);
}
