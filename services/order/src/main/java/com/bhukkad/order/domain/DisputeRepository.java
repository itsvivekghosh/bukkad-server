package com.bhukkad.order.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DisputeRepository extends JpaRepository<Dispute, Long> {

    Optional<Dispute> findByOrderId(Long orderId);

    boolean existsByOrderId(Long orderId);

    List<Dispute> findByOrderIdInOrderByCreatedAtDesc(List<Long> orderIds);

    List<Dispute> findByStatusInOrderByCreatedAtAsc(List<Dispute.DisputeStatus> statuses);

    List<Dispute> findAllByOrderByCreatedAtDesc();
}