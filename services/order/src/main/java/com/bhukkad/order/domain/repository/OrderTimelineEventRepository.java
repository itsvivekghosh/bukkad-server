package com.bhukkad.order.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import com.bhukkad.order.domain.entity.OrderTimelineEvent;

public interface OrderTimelineEventRepository extends JpaRepository<OrderTimelineEvent, Long> {
    List<OrderTimelineEvent> findByOrderId(Long orderId);
    List<OrderTimelineEvent> findByOrderIdOrderByCreatedAtAsc(Long orderId);
}
