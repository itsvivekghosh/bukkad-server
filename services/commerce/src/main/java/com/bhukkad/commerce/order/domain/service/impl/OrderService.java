package com.bhukkad.commerce.order.domain.service.impl;

import com.bhukkad.commerce.order.api.dto.request.CreateOrderRequest;
import com.bhukkad.commerce.order.api.dto.response.OrderDetailsResponse;
import com.bhukkad.commerce.order.api.dto.response.OrderResponse;
import com.bhukkad.commerce.order.domain.entity.Order;
import com.bhukkad.commerce.order.domain.repository.OrderRepository;
import com.bhukkad.commerce.order.domain.repository.OrderItemRepository;
import com.bhukkad.commerce.order.domain.repository.OrderTimelineEventRepository;
import com.bhukkad.commerce.order.domain.service.impl.OrderEventPublisher;
import com.bhukkad.commerce.order.domain.service.impl.OrderCreationService;
import com.bhukkad.commerce.order.domain.service.impl.OrderLifecycleService;
import com.bhukkad.commerce.order.domain.service.impl.OrderQueryService;
import com.bhukkad.commerce.order.domain.service.impl.OrderReorderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderCreationService orderCreationService;
    private final OrderQueryService orderQueryService;
    private final OrderLifecycleService orderLifecycleService;
    private final OrderReorderService orderReorderService;

    public OrderResponse createOrder(CreateOrderRequest request) {
        return orderCreationService.createOrder(request);
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(Long orderId) {
        return orderQueryService.getOrder(orderId);
    }

    @Transactional(readOnly = true)
    public Page<OrderResponse> getOrdersForCustomer(Long customerId, Pageable pageable) {
        return orderQueryService.getOrdersForCustomer(customerId, pageable);
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> getOrdersForCustomer(Long customerId) {
        return orderQueryService.getOrdersForCustomer(customerId);
    }

    @Transactional(readOnly = true)
    public List<Order> findByCustomerIdCursor(Long customerId,
                                                      LocalDateTime cursorCreatedAt,
                                                      Long cursorId,
                                                      int pageSize) {
        return orderQueryService.findByCustomerIdCursor(customerId, cursorCreatedAt, cursorId, pageSize);
    }

    @Transactional(readOnly = true)
    public OrderDetailsResponse getOrderDetails(Long orderId) {
        return orderQueryService.getOrderDetails(orderId);
    }

    @Transactional(readOnly = true)
    public List<String> timelineEvents(Long orderId) {
        return orderQueryService.timelineEvents(orderId);
    }

    @Transactional(readOnly = true)
    public Page<OrderResponse> getOrdersPaged(Pageable pageable) {
        return orderQueryService.getOrdersPaged(pageable);
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> toResponseCompatBatch(List<Order> orders) {
        return orderQueryService.toResponseCompatBatch(orders);
    }

    @Transactional(readOnly = true)
    public OrderResponse toResponseCompat(Order order) {
        return orderQueryService.toResponseCompatBatch(List.of(order)).stream().findFirst().orElse(null);
    }

    @Transactional
    public void cancelOrder(Long orderId) {
        orderLifecycleService.cancelOrder(orderId);
    }

    @Transactional
    public OrderResponse transition(Long orderId, String newStatus, String timelineEvent) {
        return orderLifecycleService.transition(orderId, newStatus, timelineEvent);
    }

    @Transactional
    public OrderResponse assignDeliveryAgent(Long orderId, Long agentId) {
        return orderLifecycleService.assignDeliveryAgent(orderId, agentId);
    }

    @Transactional
    public OrderResponse reorder(Long customerId, Long orderId) {
        return orderReorderService.reorder(customerId, orderId);
    }
}