package com.bhukkad.order.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderTimelineEventRepository extends JpaRepository<OrderTimelineEvent, Long> {
    List<OrderTimelineEvent> findByOrderId(Long orderId);
    List<OrderTimelineEvent> findByOrderIdOrderByCreatedAtAsc(Long orderId);
}
