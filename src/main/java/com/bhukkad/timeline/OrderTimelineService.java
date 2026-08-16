package com.bhukkad.timeline;

import com.bhukkad.dto.response.OrderTimelineEventResponse;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.OrderTimelineEvent;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.repository.OrderTimelineEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Records and retrieves chronological order timeline events for tracking and support.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderTimelineService {

    private final OrderTimelineEventRepository orderTimelineEventRepository;
    private final OrderRepository orderRepository;

    /**
     * Records a new timeline event for an order.
     *
     * @param orderId   order identifier
     * @param eventType event type label
     * @param status    optional status snapshot
     * @param message   human-readable message
     * @param actorId   optional actor user ID
     * @param actorRole optional actor role
     * @return recorded event
     */
    @Transactional
    public OrderTimelineEventResponse recordEvent(Long orderId, String eventType, String status,
                                                  String message, Long actorId, String actorRole) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        OrderTimelineEvent event = new OrderTimelineEvent();
        event.setOrder(order);
        event.setEventType(eventType);
        event.setStatus(status);
        event.setMessage(message);
        event.setActorId(actorId);
        event.setActorRole(actorRole);

        return toResponse(orderTimelineEventRepository.save(event));
    }

    /**
     * Returns the full timeline for an order in chronological order.
     *
     * @param orderId order identifier
     * @return timeline events
     */
    public List<OrderTimelineEventResponse> getTimelineForOrder(Long orderId) {
        if (!orderRepository.existsById(orderId)) {
            throw new ResourceNotFoundException("Order not found");
        }
        return orderTimelineEventRepository.findByOrderIdOrderByCreatedAtAsc(orderId).stream()
                .map(this::toResponse)
                .toList();
    }

    private OrderTimelineEventResponse toResponse(OrderTimelineEvent event) {
        return OrderTimelineEventResponse.builder()
                .id(event.getId())
                .orderId(event.getOrder().getId())
                .eventType(event.getEventType())
                .status(event.getStatus())
                .message(event.getMessage())
                .actorId(event.getActorId())
                .actorRole(event.getActorRole())
                .createdAt(event.getCreatedAt() != null ? event.getCreatedAt().toString() : null)
                .build();
    }
}
