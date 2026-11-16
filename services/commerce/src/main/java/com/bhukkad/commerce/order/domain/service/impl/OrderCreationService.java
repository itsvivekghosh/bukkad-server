package com.bhukkad.commerce.order.domain.service.impl;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.saga.SagaCoordinator;
import com.bhukkad.common.saga.SagaInstance;
import com.bhukkad.common.saga.SagaStepDefinition;
import com.bhukkad.common.security.ServiceJwtAuthTokenProvider;
import com.bhukkad.commerce.order.api.dto.request.CreateOrderRequest;
import com.bhukkad.commerce.order.api.dto.response.OrderItemDto;
import com.bhukkad.commerce.order.api.dto.response.OrderResponse;
import com.bhukkad.commerce.order.api.dto.request.OrderItemRequest;
import com.bhukkad.commerce.order.api.RestaurantPricedItemResolver;
import com.bhukkad.commerce.order.infrastructure.client.PaymentServiceClient;
import com.bhukkad.commerce.order.infrastructure.client.RestaurantClient;
import com.bhukkad.commerce.order.infrastructure.client.ChargeResponse;
import com.bhukkad.commerce.order.infrastructure.client.StockReservationLine;
import com.bhukkad.commerce.order.domain.entity.Order;
import com.bhukkad.commerce.order.domain.entity.OrderItem;
import com.bhukkad.commerce.order.domain.entity.OrderTimelineEvent;
import com.bhukkad.commerce.order.domain.repository.OrderItemRepository;
import com.bhukkad.commerce.order.domain.repository.OrderRepository;
import com.bhukkad.commerce.order.domain.repository.OrderTimelineEventRepository;
import com.bhukkad.commerce.order.domain.service.impl.OrderEventPublisher;
import com.bhukkad.commerce.order.config.OrderSagaProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCreationService {

    public static final String SAGA_TYPE = "ORDER_CREATION";
    public static final String SAGA_PAYMENT_METHOD = "WALLET";
    private static final Duration SAGA_RPC_TIMEOUT = Duration.ofSeconds(6);

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderTimelineEventRepository timelineRepository;
    private final SagaCoordinator sagaCoordinator;
    private final OrderEventPublisher eventPublisher;
    private final RestaurantClient restaurantClient;
    private final RestaurantPricedItemResolver pricedItemResolver;
    private final PaymentServiceClient paymentServiceClient;
    private final ObjectProvider<ServiceJwtAuthTokenProvider> serviceJwtTokenProvider;
    private final OrderSagaProperties asyncSaga;
    private final ObjectMapper objectMapper;

    public OrderResponse createOrder(CreateOrderRequest request) {
        if (request.items() == null || request.items().isEmpty()) {
            throw new BusinessException("Order requires at least one item");
        }
        if (request.items().stream().anyMatch(i -> i.quantity() <= 0)) {
            throw new BusinessException("Item quantity must be positive");
        }

        List<OrderItemRequest> items = rePrice(request);

        OrderCreationResult creation = persistOrderAsCreated(request, items);
        final Long orderId = creation.order().getId();
        BigDecimal total = items.stream()
                .map(i -> i.unitPrice().multiply(BigDecimal.valueOf(i.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        SagaOutcome outcome = runSaga(orderId, request, items, total);

        Order order = finalizeOrder(orderId, outcome);

        OrderResponse response = toResponse(order, creation.items());
        return response;
    }

    @Transactional
    private OrderCreationResult persistOrderAsCreated(CreateOrderRequest request,
                                                      List<OrderItemRequest> items) {
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

        List<OrderItem> savedItems = orderItemRepository.saveAll(
                items.stream().map(i -> {
                    OrderItem item = new OrderItem();
                    item.setOrderId(orderId);
                    item.setMenuItemId(i.menuItemId());
                    item.setItemName(i.name());
                    item.setUnitPrice(i.unitPrice());
                    item.setQuantity(i.quantity());
                    return item;
                }).toList()
        );
        return new OrderCreationResult(order, savedItems);
    }

    private SagaOutcome runSaga(Long orderId, CreateOrderRequest request,
                                List<OrderItemRequest> items, BigDecimal total) {
        String serviceToken = serviceToken();
        List<StockReservationLine> reservationLines = items.stream()
                .map(i -> StockReservationLine.of(i.menuItemId(), i.name(), i.quantity()))
                .toList();

        if (asyncSaga.isEnabled()) {
            return runAsyncSaga(orderId, request, items, reservationLines, total);
        }
        return runSyncSaga(orderId, request, items, total, serviceToken, reservationLines);
    }

    private SagaOutcome runAsyncSaga(Long orderId, CreateOrderRequest request,
                                     List<OrderItemRequest> items,
                                     List<StockReservationLine> reservationLines,
                                     BigDecimal total) {
        AtomicReference<Boolean> reserved = new AtomicReference<>(Boolean.FALSE);
        try {
            List<StockReservationLine> ack = blockQuietly(
                    restaurantClient.reserveStock(reservationLines, serviceToken()),
                    "RESERVE_STOCK", orderId);
            reserved.set(ack != null);
        } catch (Exception ex) {
            log.warn("SAGA_STEP_CALL_FAILED | step=RESERVE_STOCK | orderId={} | error={}",
                    orderId, ex.getMessage());
        }
        if (!Boolean.TRUE.equals(reserved.get())) {
            log.warn("ASYNC_ORDER_SAGA_RESERVE_FAILED | orderId={}", orderId);
            return null;
        }
        return new SagaOutcome(Order.STATUS_AWAITING_PAYMENT, "PAYMENT_REQUESTED",
                request.customerId(), request.restaurantId(), items, total, orderId);
    }

    private SagaOutcome runSyncSaga(Long orderId, CreateOrderRequest request,
                                    List<OrderItemRequest> items, BigDecimal total,
                                    String serviceToken, List<StockReservationLine> reservationLines) {
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

        boolean completed = saga != null
                && SagaInstance.STATUS_COMPLETED.equals(saga.getStatus());
        if (!completed) {
            log.warn("ORDER_SAGA_FAILED | orderId={} | sagaStatus={}",
                    orderId, saga == null ? "null" : saga.getStatus());
            return null;
        }
        return new SagaOutcome(Order.STATUS_CONFIRMED, "CONFIRMED",
                request.customerId(), request.restaurantId(), items, total, orderId);
    }

    @Transactional
    private Order finalizeOrder(Long orderId, SagaOutcome outcome) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException("Order not found: " + orderId));

        if (outcome == null) {
            order.setStatus(Order.STATUS_CANCELLED);
            recordTimeline(orderId, "FAILED");
            eventPublisher.orderCreated(orderId, order.getCustomerId(), order.getRestaurantId());
            eventPublisher.orderStatusChanged(orderId, Order.STATUS_CANCELLED);
            return orderRepository.save(order);
        }

        order.setStatus(outcome.status());
        recordTimeline(orderId, outcome.timelineEvent());
        eventPublisher.orderCreated(orderId, outcome.customerId(), outcome.restaurantId());

        switch (outcome.status()) {
            case Order.STATUS_CONFIRMED -> {
                eventPublisher.orderStatusChanged(orderId, Order.STATUS_CONFIRMED);
                eventPublisher.orderItemsSnapshot(orderId, outcome.restaurantId(),
                        outcome.items().stream()
                                .map(i -> new OrderEventPublisher.SnapshotItem(
                                        i.menuItemId(), i.name(), i.quantity()))
                                .toList());
            }
            case Order.STATUS_AWAITING_PAYMENT -> {
                eventPublisher.paymentRequested(orderId, outcome.customerId(),
                        outcome.total(), order.getCurrency());
                eventPublisher.orderItemsSnapshot(orderId, outcome.restaurantId(),
                        outcome.items().stream()
                                .map(i -> new OrderEventPublisher.SnapshotItem(
                                        i.menuItemId(), i.name(), i.quantity()))
                                .toList());
            }
            default -> {
            }
        }
        return orderRepository.save(order);
    }

    private String serviceToken() {
        ServiceJwtAuthTokenProvider provider = serviceJwtTokenProvider.getIfAvailable();
        return provider != null ? provider.serviceToken() : null;
    }

    private List<OrderItemRequest> rePrice(CreateOrderRequest request) {
        java.util.Set<Long> itemIds = new java.util.LinkedHashSet<>(request.items().size());
        for (OrderItemRequest i : request.items()) {
            itemIds.add(i.menuItemId());
        }
        Map<Long, RestaurantPricedItemResolver.PricedItem> priced =
                pricedItemResolver.resolveAll(itemIds);

        List<OrderItemRequest> result = new java.util.ArrayList<>(request.items().size());
        for (OrderItemRequest i : request.items()) {
            RestaurantPricedItemResolver.PricedItem authoritative = priced.get(i.menuItemId());
            if (authoritative == null) {
                throw new BusinessException(
                        "Menu item is temporarily unavailable: " + i.menuItemId());
            }
            BigDecimal serverPrice = authoritative.price()
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            result.add(new OrderItemRequest(i.menuItemId(), authoritative.name(),
                    serverPrice, i.quantity()));
        }
        return result;
    }

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
            log.error("STOCK_RELEASE_FAILED | orderId={} | error={}", orderId, ex.getMessage());
            throw new BusinessException("Stock release compensation failed for order " + orderId
                    + ": " + ex.getMessage());
        }
    }

    private void refundCharge(Long orderId, Long paymentId, String serviceToken) {
        if (paymentId == null) {
            return;
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
        try {
            return objectMapper.writeValueAsString(java.util.Map.of(
                    "orderId", orderId,
                    "customerId", request.customerId(),
                    "restaurantId", request.restaurantId(),
                    "amount", total.toPlainString()
            ));
        } catch (Exception e) {
            throw new IllegalStateException("Saga payload serialization failed", e);
        }
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

    private record OrderCreationResult(Order order, List<OrderItem> items) {
    }
}