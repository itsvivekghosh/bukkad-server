package com.bhukkad.order;

import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.order.api.dto.request.CreateOrderRequest;
import com.bhukkad.order.api.dto.request.OrderItemRequest;
import com.bhukkad.order.infrastructure.client.PaymentServiceClient;
import com.bhukkad.order.infrastructure.client.RestaurantClient;
import com.bhukkad.order.client.dto.StockReservationLine;
import com.bhukkad.order.domain.entity.Order;
import com.bhukkad.order.domain.repository.OrderRepository;
import com.bhukkad.order.domain.repository.OrderTimelineEventRepository;
import com.bhukkad.order.domain.entity.OrderTimelineEvent;
import com.bhukkad.order.domain.service.OrderService;
import com.bhukkad.order.service.PaymentSagaEventConsumer;
import com.bhukkad.order.service.StuckOrderSweep;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import reactor.core.publisher.Mono;
import org.springframework.transaction.annotation.Transactional;
import com.bhukkad.common.error.BusinessException;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Feature #3 asynchronous saga — consumer direction, compensation, and the
 * chaos property, against real PostgreSQL (Testcontainers). Kafka itself is
 * OFF in tests; the consumer/sweep methods are invoked directly (the
 * AdminCqrsEventConsumer harness precedent), the listener wiring is covered
 * by the platform-lib KafkaPlatformConfig tests.
 *
 * <p>Chaos test: create an async order, "kill" the instance between steps
 * (the payment verdict is never delivered), run the stuck-order sweep, then
 * deliver the verdicts — the order reaches a terminal state and no
 * AWAITING_PAYMENT order remains (stuck-order = 0).</p>
 */
@SpringBootTest(properties = {
        "app.order.async-saga.enabled=true",
        "app.order.async-saga.sweep-interval-ms=3600000"})
class AsyncSagaRecoveryPostgresIntegrationTest extends AbstractOrderPostgresTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private PaymentSagaEventConsumer consumer;

    @Autowired
    private StuckOrderSweep stuckOrderSweep;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderTimelineEventRepository timelineRepository;

    @Autowired
    private com.bhukkad.common.outbox.OutboxEventRepository outboxEventRepository;

    @MockBean
    private RestaurantClient restaurantClient;

    @MockBean
    private PaymentServiceClient paymentServiceClient;


    private Long createAwaitingPaymentOrder(long customerId, long restaurantId) {
        // Server-side re-pricing (money-integrity #3) resolves the menu
        // snapshot before the write; stub the batch endpoint for item 100.
        when(restaurantClient.getMenuItems(any()))
                .thenReturn(Mono.just(List.of(java.util.Map.<String, Object>of(
                        "id", 100L, "restaurantId", restaurantId,
                        "name", "Burger", "price", new BigDecimal("10.00")))));
        when(restaurantClient.reserveStock(any(), any()))
                .thenReturn(Mono.just(List.of(StockReservationLine.of(100L, "Burger", 1))));
        var response = orderService.createOrder(new CreateOrderRequest(
                customerId, restaurantId,
                List.of(new OrderItemRequest(100L, "Burger", BigDecimal.valueOf(10.00), 1))));
        assertThat(response.status()).isEqualTo(Order.STATUS_AWAITING_PAYMENT);
        return response.id();
    }

    private String settledPayload(Long orderId, Long paymentId) {
        return envelope("payment_settled", "{\"orderId\":%d,\"paymentId\":%d}".formatted(orderId, paymentId));
    }

    private String failedPayload(Long orderId, String reason) {
        return envelope("payment_failed",
                "{\"orderId\":%d,\"reason\":\"%s\"}".formatted(orderId, reason));
    }

    private String envelope(String type, String payload) {
        return PlatformEventMessage.of(type, "42", payload).toJson();
    }

    @Test
    void paymentSettled_confirmsOrderAndEmitsDispatchSignal() {
        Long orderId = createAwaitingPaymentOrder(11L, 30L);

        consumer.onPaymentEvent(settledPayload(orderId, 77L));

        Order order = orderRepository.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(Order.STATUS_CONFIRMED);
        // Dispatch signal = OrderStatusChanged → CONFIRMED (delivery/notification).
        String statusChanged = outboxEventRepository.findAll().stream()
                .filter(e -> "OrderStatusChanged".equals(e.getEventType()))
                .filter(e -> orderId.equals(e.getAggregateId()))
                .map(com.bhukkad.common.outbox.OutboxEvent::getPayload)
                .reduce((a, b) -> b)
                .orElseThrow();
        // The outbox row's payload is the JSON envelope; the inner status is
        // escaped, so the quoted CONFIRMED value appears as \"CONFIRMED\".
        assertThat(statusChanged).contains("\\\"CONFIRMED\\\"");
        assertThat(timelineRepository.findByOrderIdOrderByCreatedAtAsc(orderId))
                .anyMatch(t -> "CONFIRMED".equals(t.getEventType()));
    }

    @Test
    void paymentSettled_isIdempotentUnderRedelivery() {
        Long orderId = createAwaitingPaymentOrder(12L, 31L);

        consumer.onPaymentEvent(settledPayload(orderId, 78L));
        consumer.onPaymentEvent(settledPayload(orderId, 78L)); // at-least-once redelivery

        Order order = orderRepository.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(Order.STATUS_CONFIRMED);
        List<OrderTimelineEvent> events = timelineRepository.findByOrderIdOrderByCreatedAtAsc(orderId)
                .stream()
                .filter(t -> "CONFIRMED".equals(t.getEventType()))
                .collect(Collectors.toList());
        assertThat(events).hasSize(1);
    }

    @Test
    void paymentFailed_compensatesAndReleasesStockViaOutboxDrivenStep() {
        Long orderId = createAwaitingPaymentOrder(13L, 32L);
        when(restaurantClient.releaseStock(any(), any()))
                .thenReturn(Mono.just(List.of()));

        consumer.onPaymentEvent(failedPayload(orderId, "insufficient_funds"));
        // status flip + reverse outbox event happened atomically:
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(Order.STATUS_PAYMENT_FAILED);
        verify(restaurantClient, never()).releaseStock(any(), any()); // not inline!

        // The reverse-event step performs the release (compensation STEP runs
        // outbox-driven, never inline in the failed-payment tx):
        consumer.onPaymentEvent(releaseEnvelope(orderId));

        verify(restaurantClient).releaseStock(any(), any());
    }

    @Test
    @Transactional
    void chaos_killBetweenSteps_thenRecoverStaleSweepsAndVerdictCompletesTheSaga_stuckOrdersZero() {
        Long settledOrder = createAwaitingPaymentOrder(14L, 33L);
        Long failedOrder = createAwaitingPaymentOrder(15L, 34L);
        when(restaurantClient.releaseStock(any(), any()))
                .thenReturn(Mono.just(List.of()));

        // KILL between steps: no verdict ever arrives; the orders age past the
        // sweep threshold (updated_at is backdated to simulate elapsed time).
        orderRepository.findAll().forEach(order -> {
            if (Order.STATUS_AWAITING_PAYMENT.equals(order.getStatus())) {
                orderRepository.updateUpdatedAt(order.getId(), java.time.LocalDateTime.now().minusMinutes(30));
            }
        });

        stuckOrderSweep.sweepStuckOrders();

        // Swept orders are terminal-compensated: the sweep decided their fate
        // so a late verdict cannot resurrect a compensated order.
        assertThat(orderRepository.findById(settledOrder).orElseThrow().getStatus())
                .isEqualTo(Order.STATUS_PAYMENT_FAILED);
        assertThat(orderRepository.findById(failedOrder).orElseThrow().getStatus())
                .isEqualTo(Order.STATUS_PAYMENT_FAILED);

        // The sweep also emitted reverse release events; executing them is a
        // safe no-op replay of the conditional release.
        consumer.onPaymentEvent(releaseEnvelope(failedOrder));
        verify(restaurantClient).releaseStock(any(), any());

        // A late settled verdict for an order the sweep already compensated is
        // rejected (state guard) — the saga stays consistent.
        try {
            consumer.onPaymentEvent(settledPayload(settledOrder, 79L));
        } catch (com.bhukkad.common.error.BusinessException ex) {
            // expected
        }
        assertThat(orderRepository.findById(settledOrder).orElseThrow().getStatus())
                .isEqualTo(Order.STATUS_PAYMENT_FAILED);

        // Stuck-order = 0 across the whole table.
        assertThat(orderRepository.countByStatus(Order.STATUS_AWAITING_PAYMENT)).isZero();
    }

    private String releaseEnvelope(Long orderId) {
        String lines = "[{\"menuItemId\":100,\"name\":\"Burger\",\"quantity\":1}]";
        return envelope("stock_release_requested", "{\"orderId\":%d,\"lines\":%s}".formatted(orderId, lines));
    }
}
