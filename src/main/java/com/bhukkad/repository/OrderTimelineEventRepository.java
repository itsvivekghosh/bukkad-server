package com.bhukkad.repository;

import com.bhukkad.entity.OrderTimelineEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderTimelineEventRepository extends JpaRepository<OrderTimelineEvent, Long> {
    List<OrderTimelineEvent> findByOrderIdOrderByCreatedAtAsc(Long orderId);
}
