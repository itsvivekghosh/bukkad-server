package com.bhukkad.order.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.order.domain.Order;
import com.bhukkad.order.domain.OrderRepository;
import com.bhukkad.order.domain.OrderTimelineEvent;
import com.bhukkad.order.domain.OrderTimelineEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;

/**
 * Order status transitions with timeline recording (port of the monolith's
 * {@code OrderStatusService}). Enforces a valid state machine and emits an
 * {@code OrderStatusChanged} outbox event on every transition.
 */
@Service
@RequiredArgsConstructor
public class OrderStatusService {

    private static final Map<String, Set<String>> ALLOWED = Map.of(
            Order.STATUS_CREATED, Set.of(Order.STATUS_CONFIRMED, Order.STATUS_CANCELLED),
            Order.STATUS_CONFIRMED, Set.of(Order.STATUS_PREPARING, Order.STATUS_CANCELLED),
            Order.STATUS_PREPARING, Set.of(Order.STATUS_READY_FOR_PICKUP),
            Order.STATUS_READY_FOR_PICKUP, Set.of(Order.STATUS_OUT_FOR_DELIVERY),
            Order.STATUS_OUT_FOR_DELIVERY, Set.of(Order.STATUS_DELIVERED),
            Order.STATUS_CANCELLED, Set.of(),
            Order.STATUS_DELIVERED, Set.of()
    );

    private final OrderRepository orderRepository;
    private final OrderTimelineEventRepository timelineRepository;
    private final OrderEventPublisher eventPublisher;

    @Transactional
    public Order transition(Long orderId, String targetStatus) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
        Set<String> next = ALLOWED.get(order.getStatus());
        if (next == null || !next.contains(targetStatus)) {
            throw new BusinessException("Invalid transition %s -> %s".formatted(order.getStatus(), targetStatus));
        }
        order.setStatus(targetStatus);
        orderRepository.save(order);
        recordTimeline(orderId, targetStatus);
        eventPublisher.orderStatusChanged(orderId, targetStatus);
        return order;
    }

    private void recordTimeline(Long orderId, String eventType) {
        OrderTimelineEvent event = new OrderTimelineEvent();
        event.setOrderId(orderId);
        event.setEventType(eventType);
        timelineRepository.save(event);
    }
}