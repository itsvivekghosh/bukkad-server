package com.bhukkad.repository;

import com.bhukkad.entity.OrderTimelineEvent;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderTimelineEventRepository extends JpaRepository<OrderTimelineEvent, Long> {

    /**
     * Reads an order's timeline events together with their {@code order}
     * association in a single query. {@code OrderTimelineService.toResponse}
     * dereferences {@code event.getOrder().getId()}, so without this entity
     * graph every event would trigger a separate lazy SELECT (N+1).
     */
    @EntityGraph(attributePaths = "order")
    List<OrderTimelineEvent> findByOrderIdOrderByCreatedAtAsc(Long orderId);
}
