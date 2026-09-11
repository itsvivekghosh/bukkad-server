package com.bhukkad.order.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.common.saga.SagaCoordinator;
import com.bhukkad.common.saga.SagaInstance;
import com.bhukkad.common.saga.SagaStepDefinition;
import com.bhukkad.common.security.ServiceJwtAuthTokenProvider;
import com.bhukkad.order.api.CreateOrderRequest;
import com.bhukkad.order.api.OrderDetailsResponse;
import com.bhukkad.order.api.OrderItemDto;
import com.bhukkad.order.api.OrderItemRequest;
import com.bhukkad.order.api.OrderResponse;
import com.bhukkad.order.api.RestaurantPricedItemResolver;
import com.bhukkad.order.client.PaymentServiceClient;
import com.bhukkad.order.client.RestaurantClient;
import com.bhukkad.order.client.dto.ChargeResponse;
import com.bhukkad.order.client.dto.StockReservationLine;
import com.bhukkad.order.domain.Order;
import com.bhukkad.order.domain.OrderItem;
import com.bhukkad.order.domain.OrderItemRepository;
import com.bhukkad.order.domain.OrderRepository;
import com.bhukkad.order.domain.OrderTimelineEvent;
import com.bhukkad.order.domain.OrderTimelineEventRepository;
import com.bhukkad.order.OrderSagaProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Order creation as a saga: {@code RESERVE_STOCK → CHARGE_PAYMENT}, with
 * compensation in reverse on failure. Both steps execute for real (audit
 * batch A): RESERVE_STOCK calls the restaurant inventory API, CHARGE_PAYMENT
 * calls the payment service. A {@link SagaStepDefinition.StepResult#FAILED}
 * step marks the saga step FAILED, unwinds the compensation chain (a charge
 * failure releases the reserved stock; a post-charge failure refunds it) and
 * leaves the order {@code CANCELLED} with a timeline entry and a status event.
 * The outbox emits {@code OrderCreated}/{@code OrderStatusChanged} in the same
 * transaction that commits the order (plan §6.4 — at-least-once + idempotent
 * consumers).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    public static final String SAGA_TYPE = "ORDER_CREATION";

    /** Payment method charged by the create-order saga until the checkout API carries a method. */
    public static final String SAGA_PAYMENT_METHOD = "WALLET";

    /** Outer bound for a saga RPC (client already retries/timeouts internally). */
    private static final Duration SAGA_RPC_TIMEOUT = Duration.ofSeconds(20);

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderTimelineEventRepository timelineRepository;
    private final SagaCoordinator sagaCoordinator;
    private final OrderEventPublisher eventPublisher;
    private final RestaurantClient restaurantClient;
    private final RestaurantPricedItemResolver pricedItemResolver;
    private final PaymentServiceClient paymentServiceClient;
    private final ObjectProvider<ServiceJwtAuthTokenProvider> serviceJwtTokenProvider;
    private final com.bhukkad.order.OrderSagaProperties asyncSaga;

    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request) {
        if (request.items() == null || request.items().isEmpty()) {
            throw new BusinessException("Order requires at least one item");
        }
        if (request.items().stream().anyMatch(i -> i.quantity() <= 0)) {
            throw new BusinessException("Item quantity must be positive");
        }

        // Money-integrity (roadmap #3): client-supplied unitPrice is NEVER
        // trusted. Every line is re-priced from the restaurant menu snapshot
        // (cached batch endpoint) BEFORE the write transaction; a missing id
        // means the item does not exist or is unavailable/inactive and
        // rejects the order (resolver's existing error conventions: 400 for
        // missing/unavailable items, 503 UpstreamUnavailableException for a
        // restaurant outage — never a silent fallback to client prices).
        List<OrderItemRequest> items = rePrice(request);

        BigDecimal total = items.stream()
                .map(i -> i.unitPrice().multiply(BigDecimal.valueOf(i.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Order order = new Order();
        order.setCustomerId(request.customerId());
        order.setRestaurantId(request.restaurantId());
        order.setStatus(Order.STATUS_CREATED);
        order.setTotalAmount(total);
        order = orderRepository.save(order);
        final Long orderId = order.getId();

        items.forEach(i -> {
            OrderItem item = new OrderItem();
            item.setOrderId(orderId);
            item.setMenuItemId(i.menuItemId());
            item.setItemName(i.name());
            item.setUnitPrice(i.unitPrice());
            item.setQuantity(i.quantity());
            orderItemRepository.save(item);
        });

        // Saga (audit batch A — real execution): reserve stock -> charge
        // payment. A FAILED step marks the saga step FAILED, unwinds the
        // compensation chain and leaves the order CANCELLED:
        //   - CHARGE_PAYMENT failure -> RESERVE_STOCK compensation (releaseStock)
        //   - a completed charge is compensated by refunding it.
        //
        // Feature #3 (async saga, app.order.async-saga.enabled=true): the
        // CHARGE_PAYMENT step becomes outbox-driven. After the stock
        // reservation succeeds the order flips to AWAITING_PAYMENT and a
        // payment_requested event is enqueued in the SAME transaction; the
        // payment service's consumer (W1-MONEY) settles/fails it and the
        // PaymentSagaEventConsumer drives CONFIRMED/compensation from there.
        String serviceToken = serviceToken();
        List<StockReservationLine> reservationLines = items.stream()
                .map(i -> StockReservationLine.of(i.menuItemId(), i.name(), i.quantity()))
                .toList();

        if (asyncSaga.isEnabled()) {
            AtomicReference<Boolean> reserved = new AtomicReference<>(Boolean.FALSE);
            try {
                List<StockReservationLine> ack = blockQuietly(
                        restaurantClient.reserveStock(reservationLines, serviceToken),
                        "RESERVE_STOCK", orderId);
                reserved.set(ack != null);
            } catch (Exception ex) {
                log.warn("SAGA_STEP_CALL_FAILED | step=RESERVE_STOCK | orderId={} | error={}",
                        orderId, ex.getMessage());
            }
            if (!Boolean.TRUE.equals(reserved.get())) {
                order.setStatus(Order.STATUS_CANCELLED);
                orderRepository.save(order);
                recordTimeline(orderId, "FAILED");
                eventPublisher.orderCreated(orderId, request.customerId(), request.restaurantId());
                eventPublisher.orderStatusChanged(orderId, Order.STATUS_CANCELLED);
                log.warn("ASYNC_ORDER_SAGA_RESERVE_FAILED | orderId={}", orderId);
                return toResponse(order);
            }
            order.setStatus(Order.STATUS_AWAITING_PAYMENT);
            orderRepository.save(order);
            recordTimeline(orderId, "PAYMENT_REQUESTED");
            eventPublisher.orderCreated(orderId, request.customerId(), request.restaurantId());
            eventPublisher.orderItemsSnapshot(orderId, request.restaurantId(),
                    items.stream()
                            .map(i -> new OrderEventPublisher.SnapshotItem(i.menuItemId(), i.name(), i.quantity()))
                            .toList());
            // G-1: the payment request commits atomically with the order; the
            // payment verdict comes back through payment.events.v1.
            eventPublisher.paymentRequested(orderId, request.customerId(), total, order.getCurrency());
            return toResponse(order);
        }

        AtomicReference<Long> chargedPaymentId = new AtomicReference<>();

        SagaStepDefinition reserve = SagaStepDefinition.executionResult(
                "RESERVE_STOCK",
                () -> {
                    List<StockReservationLine> reserved = blockQuietly(
                            restaurantClient.reserveStock(reservationLines, serviceToken),
                            "RESERVE_STOCK", orderId);
                    return reserved != null
                            ? SagaStepDefinition.StepResult.SUCCESS
                            : SagaStepDefinition.StepResult.FAILED;
                },
                payload -> releaseReservations(orderId, reservationLines, serviceToken));

        SagaStepDefinition charge = SagaStepDefinition.executionResult(
                "CHARGE_PAYMENT",
                () -> {
                    ChargeResponse response = blockQuietly(
                            paymentServiceClient.charge(orderId, request.customerId(), total,
                                    SAGA_PAYMENT_METHOD, "ORDER-" + orderId, serviceToken),
                            "CHARGE_PAYMENT", orderId);
                    if (response == null) {
                        return SagaStepDefinition.StepResult.FAILED;
                    }
                    chargedPaymentId.set(response.paymentId());
                    return SagaStepDefinition.StepResult.SUCCESS;
                },
                payload -> refundCharge(orderId, chargedPaymentId.get(), serviceToken));

        SagaInstance saga = sagaCoordinator.executeSaga(
                SAGA_TYPE, String.valueOf(orderId), sagaPayload(orderId, request, total),
                List.of(reserve, charge));

        if (saga != null && !SagaInstance.STATUS_COMPLETED.equals(saga.getStatus())) {
            // The saga unwound (COMPENSATED) or could not be confirmed: the
            // order is placed in its terminal CANCELLED state with a timeline
            // entry and a status event — stock/payment side effects are undone.
            order.setStatus(Order.STATUS_CANCELLED);
            orderRepository.save(order);
            recordTimeline(orderId, "FAILED");
            eventPublisher.orderCreated(orderId, request.customerId(), request.restaurantId());
            eventPublisher.orderStatusChanged(orderId, Order.STATUS_CANCELLED);
            log.warn("ORDER_SAGA_FAILED | orderId={} | sagaStatus={}", orderId, saga.getStatus());
            return toResponse(order);
        }

        order.setStatus(Order.STATUS_CONFIRMED);
        orderRepository.save(order);
        recordTimeline(orderId, "CONFIRMED");

        eventPublisher.orderCreated(orderId, request.customerId(), request.restaurantId());
        eventPublisher.orderStatusChanged(orderId, Order.STATUS_CONFIRMED);
        // Trending feed (survey OrderItemsSnapshotConsumer contract) — same tx
        // as the order (deliverable 1).
        eventPublisher.orderItemsSnapshot(orderId, request.restaurantId(),
                items.stream()
                        .map(i -> new OrderEventPublisher.SnapshotItem(i.menuItemId(), i.name(), i.quantity()))
                        .toList());

        return toResponse(order);
    }

    private String serviceToken() {
        ServiceJwtAuthTokenProvider provider = serviceJwtTokenProvider.getIfAvailable();
        return provider != null ? provider.serviceToken() : null;
    }

    /**
     * Re-prices every line from the restaurant's menu snapshot so the money
     * path carries only server-computed values. One cached batch call covers
     * the whole order (PERF-3 chord). Every create surface (canonical,
     * customer-nested and legacy-compat controllers, batch checkout, reorder
     * and the async job) funnels through here, so client-supplied
     * {@code unitPrice}/{@code name} can never reach the order entity, the
     * saga payload, the payment charge or the outbox events.
     *
     * <p>Reuses {@link RestaurantPricedItemResolver} and its error
     * conventions: an id the restaurant does not return (nonexistent,
     * unavailable or inactive) rejects the order with
     * {@link BusinessException}; a restaurant outage surfaces as
     * {@code UpstreamUnavailableException} (503) instead of faking a missing
     * item. Fail-closed: there is no fallback to client prices.</p>
     */
    private List<OrderItemRequest> rePrice(CreateOrderRequest request) {
        Map<Long, RestaurantPricedItemResolver.PricedItem> priced =
                pricedItemResolver.resolveAll(request.items().stream()
                        .map(OrderItemRequest::menuItemId)
                        .toList());
        return request.items().stream()
                .map(i -> {
                    RestaurantPricedItemResolver.PricedItem authoritative = priced.get(i.menuItemId());
                    if (authoritative == null) {
                        // Not in the restaurant's answer (e.g. a null id was
                        // never requestable): treat as unavailable.
                        throw new BusinessException(
                                "Menu item is temporarily unavailable: " + i.menuItemId());
                    }
                    // Menu prices are numeric(10,2); normalizing to the same
                    // scale keeps stored money and the frozen W1-MONEY amount
                    // string deterministic across the JSON round-trip.
                    BigDecimal serverPrice = authoritative.price()
                            .setScale(2, java.math.RoundingMode.HALF_UP);
                    return new OrderItemRequest(i.menuItemId(), authoritative.name(),
                            serverPrice, i.quantity());
                })
                .toList();
    }

    /**
     * Awaits a saga RPC; transport/HTTP failures degrade to {@code null} so
     * the caller can report {@link SagaStepDefinition.StepResult#FAILED} —
     * the saga engine, not the client stack, owns the failure semantics.
     */
    private <T> T blockQuietly(reactor.core.publisher.Mono<T> call, String stepName, Long orderId) {
        try {
            return call.block(SAGA_RPC_TIMEOUT);
        } catch (Exception ex) {
            log.warn("SAGA_STEP_CALL_FAILED | step={} | orderId={} | error={}",
                    stepName, orderId, ex.getMessage());
            return null;
        }
    }

    private void releaseReservations(Long orderId, List<StockReservationLine> lines, String serviceToken) {
        try {
            restaurantClient.releaseStock(lines, serviceToken).block(SAGA_RPC_TIMEOUT);
            log.info("STOCK_RELEASED | orderId={}", orderId);
        } catch (Exception ex) {
            // Compensation itself failed: propagate so the SagaCoordinator marks
            // the saga FAILED (the money/stock unwind is incomplete) instead of
            // pretending the chain closed cleanly.
            log.error("STOCK_RELEASE_FAILED | orderId={} | error={}", orderId, ex.getMessage());
            throw new BusinessException("Stock release compensation failed for order " + orderId
                    + ": " + ex.getMessage());
        }
    }

    private void refundCharge(Long orderId, Long paymentId, String serviceToken) {
        if (paymentId == null) {
            return; // the charge never completed — nothing to refund
        }
        try {
            paymentServiceClient.refund(paymentId, "saga-compensation", serviceToken)
                    .block(SAGA_RPC_TIMEOUT);
            log.info("PAYMENT_REFUNDED | orderId={} | paymentId={}", orderId, paymentId);
        } catch (Exception ex) {
            log.error("PAYMENT_REFUND_FAILED | orderId={} | paymentId={} | error={}",
                    orderId, paymentId, ex.getMessage());
            throw new BusinessException("Payment refund compensation failed for order " + orderId
                    + ": " + ex.getMessage());
        }
    }

    private String sagaPayload(Long orderId, CreateOrderRequest request, BigDecimal total) {
        return "{\"orderId\":" + orderId
                + ",\"customerId\":" + request.customerId()
                + ",\"restaurantId\":" + request.restaurantId()
                + ",\"amount\":\"" + total.toPlainString() + "\"}";
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