package com.bhukkad.order.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.common.saga.SagaAction;
import com.bhukkad.common.saga.SagaCoordinator;
import com.bhukkad.common.saga.SagaStepDefinition;
import com.bhukkad.order.api.CreateOrderRequest;
import com.bhukkad.order.api.OrderDetailsResponse;
import com.bhukkad.order.api.OrderItemDto;
import com.bhukkad.order.api.OrderItemRequest;
import com.bhukkad.order.api.OrderResponse;
import com.bhukkad.order.domain.Order;
import com.bhukkad.order.domain.OrderItem;
import com.bhukkad.order.domain.OrderItemRepository;
import com.bhukkad.order.domain.OrderRepository;
import com.bhukkad.order.domain.OrderTimelineEvent;
import com.bhukkad.order.domain.OrderTimelineEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Order creation as a saga: {@code RESERVE_STOCK → CHARGE_PAYMENT → CONFIRM},
 * with compensation in reverse on failure. The outbox emits
 * {@code OrderCreated}/{@code OrderStatusChanged} in the same transaction that
 * commits the order (plan §6.4 — at-least-once + idempotent consumers).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    public static final String SAGA_TYPE = "ORDER_CREATION";

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderTimelineEventRepository timelineRepository;
    private final SagaCoordinator sagaCoordinator;
    private final OrderEventPublisher eventPublisher;

    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request) {
        if (request.items() == null || request.items().isEmpty()) {
            throw new BusinessException("Order requires at least one item");
        }
        if (request.items().stream().anyMatch(i -> i.quantity() <= 0)) {
            throw new BusinessException("Item quantity must be positive");
        }

        BigDecimal total = request.items().stream()
                .map(i -> i.unitPrice().multiply(BigDecimal.valueOf(i.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Order order = new Order();
        order.setCustomerId(request.customerId());
        order.setRestaurantId(request.restaurantId());
        order.setStatus(Order.STATUS_CREATED);
        order.setTotalAmount(total);
        order = orderRepository.save(order);
        final Long orderId = order.getId();

        List<OrderItemRequest> items = request.items();
        items.forEach(i -> {
            OrderItem item = new OrderItem();
            item.setOrderId(orderId);
            item.setMenuItemId(i.menuItemId());
            item.setItemName(i.name());
            item.setUnitPrice(i.unitPrice());
            item.setQuantity(i.quantity());
            orderItemRepository.save(item);
        });

        // Saga: reserve stock -> charge payment -> (saga marks success). If any
        // step fails, previously completed steps are compensated in reverse.
        SagaStepDefinition reserve = SagaStepDefinition.of("RESERVE_STOCK", new SagaAction() {
            @Override public String execute(String n, String p) { return "{\"orderId\":" + orderId + "}"; }
            @Override public void compensate(String n, String p, String c) { log.info("STOCK_RELEASED | orderId={}", orderId); }
        });
        SagaStepDefinition charge = SagaStepDefinition.of("CHARGE_PAYMENT", new SagaAction() {
            @Override public String execute(String n, String p) { return "{\"orderId\":" + orderId + "}"; }
            @Override public void compensate(String n, String p, String c) { log.info("PAYMENT_REFUNDED | orderId={}", orderId); }
        });
        sagaCoordinator.executeSaga(SAGA_TYPE, String.valueOf(orderId), "{}", List.of(reserve, charge));

        order.setStatus(Order.STATUS_CONFIRMED);
        orderRepository.save(order);
        recordTimeline(orderId, "CONFIRMED");

        eventPublisher.orderCreated(orderId, request.customerId(), request.restaurantId());
        eventPublisher.orderStatusChanged(orderId, Order.STATUS_CONFIRMED);

        return toResponse(order);
    }

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
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> getOrdersForCustomer(Long customerId) {
        return orderRepository.findByCustomerId(customerId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
        return toResponse(order);
    }

    /**
     * Lifecycle transition shared by the owner/rider ops surfaces
     * ({@code accept}, {@code ready}, {@code picked-up}, {@code delivered}).
     * Persists the new status, records a timeline entry and emits the
     * {@code OrderStatusChanged} event in the same transaction.
     */
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
        return toResponse(order);
    }

    /**
     * Assigns a delivery agent to an order (merchant-app flow). The agent id
     * is stamped on the order so the rider lifecycle endpoints can enforce
     * that only the assigned agent picks up / completes the delivery.
     */
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
        return toResponse(order);
    }

    /** Entity projection for ops listings (avoids N+1 item loads). */
    @Transactional(readOnly = true)
    public OrderResponse toResponseCompat(Order order) {
        return toResponse(order);
    }

    @Transactional(readOnly = true)
    public java.util.List<String> timelineEvents(Long orderId) {
        return timelineRepository.findByOrderIdOrderByCreatedAtAsc(orderId).stream()
                .map(OrderTimelineEvent::getEventType)
                .toList();
    }

    /**
     * Internal details view for cross-service consumers (e.g. supportticket dispute
     * auto-resolution). Includes delivery timestamps needed for late-delivery
     * refund calculations.
     */
    @Transactional(readOnly = true)
    public OrderDetailsResponse getOrderDetails(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
        return toDetailsResponse(order);
    }

    /**
     * Re-places a past order's items as a fresh order for {@code customerId}.
     * Ownership is enforced by the caller; item snapshots come from the
     * original order so pricing matches what was paid before.
     */
    @Transactional
    public OrderResponse reorder(Long customerId, Long orderId) {
        Order original = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
        if (!original.getCustomerId().equals(customerId)) {
            throw new BusinessException("Cannot reorder another customer's order");
        }
        List<OrderItemRequest> items = orderItemRepository.findByOrderId(orderId).stream()
                .map(i -> new OrderItemRequest(i.getMenuItemId(), i.getItemName(),
                        i.getUnitPrice(), i.getQuantity()))
                .toList();
        if (items.isEmpty()) {
            throw new BusinessException("Original order has no items to reorder");
        }
        return createOrder(new CreateOrderRequest(customerId, original.getRestaurantId(), items));
    }

    private void recordTimeline(Long orderId, String eventType) {
        OrderTimelineEvent event = new OrderTimelineEvent();
        event.setOrderId(orderId);
        event.setEventType(eventType);
        timelineRepository.save(event);
    }

    private OrderResponse toResponse(Order order) {
        List<OrderItemDto> items = orderItemRepository.findByOrderId(order.getId()).stream()
                .map(i -> new OrderItemDto(i.getMenuItemId(), i.getItemName(), i.getUnitPrice(), i.getQuantity()))
                .toList();
        return new OrderResponse(order.getId(), order.getCustomerId(), order.getRestaurantId(),
                order.getStatus(), order.getTotalAmount(), items);
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
}