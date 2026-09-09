package com.bhukkad.order.service;

import com.bhukkad.order.api.OrderTimelineEventResponse;
import com.bhukkad.order.domain.OrderTimelineEvent;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.order.domain.OrderRepository;
import com.bhukkad.order.domain.OrderTimelineEventRepository;
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
     * @return recorded event
     */
    @Transactional
    public OrderTimelineEventResponse recordEvent(Long orderId, String eventType) {
        if (!orderRepository.existsById(orderId)) {
            throw new ResourceNotFoundException("Order not found");
        }

        OrderTimelineEvent event = new OrderTimelineEvent();
        event.setOrderId(orderId);
        event.setEventType(eventType);

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
                .orderId(event.getOrderId())
                .eventType(event.getEventType())
                .createdAt(event.getCreatedAt() != null ? event.getCreatedAt().toString() : null)
                .build();
    }
}
