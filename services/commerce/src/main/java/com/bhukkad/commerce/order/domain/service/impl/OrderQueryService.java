package com.bhukkad.commerce.order.domain.service.impl;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.commerce.order.api.dto.response.OrderDetailsResponse;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderQueryService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderTimelineEventRepository timelineRepository;
    private final ObjectProvider<RedisCacheService> cacheProvider;

    private static final String ORDER_CACHE_KEY_PREFIX = "order:";
    private static final String CUSTOMER_ORDERS_CACHE_KEY_PREFIX = "orders:customer:";
    private static final long ORDER_CACHE_TTL_SECONDS = 30;
    private static final long CUSTOMER_ORDERS_CACHE_TTL_SECONDS = 60;

    @Transactional(readOnly = true)
    public OrderResponse getOrder(Long orderId) {
        RedisCacheService cache = cacheProvider.getIfAvailable();
        if (cache != null) {
            String key = ORDER_CACHE_KEY_PREFIX + orderId;
            return cache.getOrCompute(key, OrderResponse.class, ORDER_CACHE_TTL_SECONDS, () -> {
                Order order = orderRepository.findById(orderId)
                        .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
                List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
                return toResponse(order, items);
            });
        }
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
        List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
        return toResponse(order, items);
    }

    @Transactional(readOnly = true)
    public Page<OrderResponse> getOrdersForCustomer(Long customerId, Pageable pageable) {
        Page<Order> page = orderRepository.findByCustomerId(customerId, pageable);
        List<Long> orderIds = page.getContent().stream().map(Order::getId).toList();
        if (orderIds.isEmpty()) {
            return page.map(o -> toResponse(o, List.of()));
        }
        Map<Long, List<OrderItem>> itemsByOrderId = orderItemRepository.findByOrderIdIn(orderIds).stream()
                .collect(Collectors.groupingBy(OrderItem::getOrderId));
        return page.map(order -> toResponse(order,
                itemsByOrderId.getOrDefault(order.getId(), List.of())));
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> getOrdersForCustomer(Long customerId) {
        Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 100);
        return getOrdersForCustomer(customerId, pageable).stream()
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public OrderDetailsResponse getOrderDetails(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
        return toDetailsResponse(order);
    }

    @Transactional(readOnly = true)
    public List<String> timelineEvents(Long orderId) {
        return timelineRepository.findByOrderIdOrderByCreatedAtAsc(orderId).stream()
                .map(OrderTimelineEvent::getEventType)
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<OrderResponse> getOrdersPaged(Pageable pageable) {
        Page<Order> page = orderRepository.findAll(pageable);
        List<Long> orderIds = page.getContent().stream().map(Order::getId).toList();
        if (orderIds.isEmpty()) {
            return page.map(o -> toResponse(o, List.of()));
        }
        Map<Long, List<OrderItem>> itemsByOrderId = orderItemRepository.findByOrderIdIn(orderIds).stream()
                .collect(Collectors.groupingBy(OrderItem::getOrderId));
        return page.map(order -> toResponse(order,
                itemsByOrderId.getOrDefault(order.getId(), List.of())));
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> toResponseCompatBatch(List<Order> orders) {
        if (orders.isEmpty()) {
            return List.of();
        }
        List<Long> orderIds = orders.stream().map(Order::getId).toList();
        Map<Long, List<OrderItem>> itemsByOrderId = orderItemRepository.findByOrderIdIn(orderIds).stream()
                .collect(Collectors.groupingBy(OrderItem::getOrderId));
        return orders.stream()
                .map(order -> toResponse(order,
                        itemsByOrderId.getOrDefault(order.getId(), List.of())))
                .toList();
    }

    private OrderResponse toResponse(Order order, List<OrderItem> items) {
        List<OrderItemDto> itemDtos = items.stream()
                .map(i -> new OrderItemDto(i.getMenuItemId(), i.getItemName(), i.getUnitPrice(), i.getQuantity()))
                .toList();
        return new OrderResponse(order.getId(), order.getCustomerId(), order.getRestaurantId(),
                order.getStatus(), order.getTotalAmount(), itemDtos);
    }

    private OrderDetailsResponse toDetailsResponse(Order order) {
        return new OrderDetailsResponse(
                order.getId(),
                order.getCustomerId(),
                order.getRestaurantId(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getDeliveredAt(),
                order.getEstimatedDeliveryAt());
    }

    @Transactional(readOnly = true)
    public List<Order> findByCustomerIdCursor(Long customerId,
                                                      LocalDateTime cursorCreatedAt,
                                                      Long cursorId,
                                                      int pageSize) {
        List<Order> orders = orderRepository.findByCustomerIdCursor(
                customerId, cursorCreatedAt, cursorId);
        return orders.stream()
                .limit(pageSize + 1)
                .toList();
    }
}
