package com.bhukkad.commerce.order.domain.service.impl;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.commerce.order.api.dto.response.OrderItemDto;
import com.bhukkad.commerce.order.api.dto.response.OrderResponse;
import com.bhukkad.commerce.order.domain.entity.Order;
import com.bhukkad.commerce.order.domain.entity.OrderItem;
import com.bhukkad.commerce.order.domain.entity.OrderTimelineEvent;
import com.bhukkad.commerce.order.domain.repository.OrderItemRepository;
import com.bhukkad.commerce.order.domain.repository.OrderRepository;
import com.bhukkad.commerce.order.domain.repository.OrderTimelineEventRepository;
import com.bhukkad.commerce.order.domain.service.impl.OrderEventPublisher;
import com.bhukkad.common.cache.RedisCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderLifecycleService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderTimelineEventRepository timelineRepository;
    private final OrderEventPublisher eventPublisher;
    private final ObjectProvider<RedisCacheService> cacheProvider;

    private static final String ORDER_CACHE_KEY_PREFIX = "order:";
    private static final String CUSTOMER_ORDERS_CACHE_KEY_PREFIX = "orders:customer:";

    @Transactional
    public void cancelOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
        if (Order.STATUS_DELIVERED.equals(order.getStatus())) {
            throw new BusinessException("Cannot cancel a delivered order");
        }
        order.setStatus(Order.STATUS_CANCELLED);
        orderRepository.save(order);
        recordTimeline(orderId, "CANCELLED");
        eventPublisher.orderStatusChanged(orderId, Order.STATUS_CANCELLED);
        invalidateOrderCache(orderId, order.getCustomerId());
    }

    @Transactional
    public OrderResponse transition(Long orderId, String newStatus, String timelineEvent) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
        if (Order.STATUS_CANCELLED.equals(order.getStatus())
                || Order.STATUS_DELIVERED.equals(order.getStatus())) {
            throw new BusinessException("Order is already " + order.getStatus());
        }
        if (Order.STATUS_DELIVERED.equals(newStatus)) {
            order.setDeliveredAt(java.time.LocalDateTime.now());
        }
        order.setStatus(newStatus);
        orderRepository.save(order);
        recordTimeline(orderId, timelineEvent);
        eventPublisher.orderStatusChanged(orderId, newStatus);
        invalidateOrderCache(orderId, order.getCustomerId());
        List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
        return toResponse(order, items);
    }

    @Transactional
    public OrderResponse assignDeliveryAgent(Long orderId, Long agentId) {
        if (agentId == null || agentId <= 0) {
            throw new BusinessException("agentId is required");
        }
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
        order.setDeliveryAgentId(agentId);
        orderRepository.save(order);
        recordTimeline(orderId, "DELIVERY_ASSIGNED");
        List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
        return toResponse(order, items);
    }

    private void recordTimeline(Long orderId, String eventType) {
        OrderTimelineEvent event = new OrderTimelineEvent();
        event.setOrderId(orderId);
        event.setEventType(eventType);
        timelineRepository.save(event);
    }

    private OrderResponse toResponse(Order order, List<OrderItem> items) {
        List<OrderItemDto> itemDtos = items.stream()
                .map(i -> new OrderItemDto(i.getMenuItemId(), i.getItemName(), i.getUnitPrice(), i.getQuantity()))
                .toList();
        return new OrderResponse(order.getId(), order.getCustomerId(), order.getRestaurantId(),
                order.getStatus(), order.getTotalAmount(), itemDtos);
    }

    private void invalidateOrderCache(Long orderId, Long customerId) {
        RedisCacheService cache = cacheProvider.getIfAvailable();
        if (cache == null) {
            return;
        }
        if (orderId != null) {
            cache.delete(ORDER_CACHE_KEY_PREFIX + orderId);
        }
        if (customerId != null) {
            cache.deletePattern(CUSTOMER_ORDERS_CACHE_KEY_PREFIX + customerId + ":*");
        }
    }
}
