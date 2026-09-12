package com.bhukkad.order;

import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.common.outbox.OutboxEvent;
import com.bhukkad.common.outbox.OutboxEventRepository;
import com.bhukkad.order.api.dto.request.CreateOrderRequest;
import com.bhukkad.order.api.dto.request.OrderItemRequest;
import com.bhukkad.order.infrastructure.client.PaymentServiceClient;
import com.bhukkad.order.infrastructure.client.RestaurantClient;
import com.bhukkad.order.client.dto.StockReservationLine;
import com.bhukkad.order.domain.entity.Order;
import com.bhukkad.order.domain.repository.OrderRepository;
import com.bhukkad.order.domain.service.OrderService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * W2-ORDER-SEARCH event contracts, asserted against the REAL outbox rows in a
 * real PostgreSQL (Testcontainers, pool-capped AbstractOrderPostgresTest):
 *
 * <ul>
 *   <li>order create (synchronous default path) → an
 *       {@code ORDER_ITEMS_SNAPSHOT} outbox row whose payload matches the
 *       survey {@code OrderItemsSnapshotConsumer} contract EXACTLY:
 *       orderId, restaurantId, items[{menuItemId, name, quantity}], orderedAt;</li>
 *   <li>async saga gate ON → a {@code payment_requested} row with the frozen
 *       W1-MONEY payload shape (orderId, customerId, amount, currency,
 *       idempotencyKey) and NO synchronous charge call;</li>
 *   <li>compensation → a reverse {@code stock_release_requested} row (G-1:
 *       compensation never performs inline broker/HTTP I/O).</li>
 * </ul>
 */
@SpringBootTest(properties = "app.order.async-saga.enabled=false")
class OrderSagaEventContractPostgresIntegrationTest extends AbstractOrderPostgresTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private org.springframework.context.ApplicationContext context;

    @Autowired
    private com.bhukkad.order.service.OrderSagaCompensationService compensationService;

    @MockBean
    private RestaurantClient restaurantClient;

    @MockBean
    private PaymentServiceClient paymentServiceClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Menu snapshot the mocked restaurant service returns for items 100/101. */
    private static Mono<List<java.util.Map<String, Object>>> menuSnapshot() {
        java.util.Map<String, Object> burger = new java.util.LinkedHashMap<>();
        burger.put("id", 100L);
        burger.put("restaurantId", 10L);
        burger.put("name", "Burger");
        burger.put("price", new BigDecimal("12.50"));
        java.util.Map<String, Object> fries = new java.util.LinkedHashMap<>();
        fries.put("id", 101L);
        fries.put("restaurantId", 10L);
        fries.put("name", "Fries");
        fries.put("price", new BigDecimal("4.00"));
        return Mono.just(List.of(burger, fries));
    }

    @Test
    void orderCreate_enqueuesOrderItemsSnapshotOutboxRow() throws Exception {
        when(restaurantClient.getMenuItems(any())).thenReturn(menuSnapshot());
        when(restaurantClient.reserveStock(any(), any()))
                .thenReturn(Mono.just(List.of(StockReservationLine.of(100L, "Burger", 1))));
        when(paymentServiceClient.charge(any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(new com.bhukkad.order.client.dto.ChargeResponse(5L, "CHARGED")));

        var request = new CreateOrderRequest(
                1L, 10L, List.of(
                new OrderItemRequest(100L, "Burger", BigDecimal.valueOf(12.50), 2),
                new OrderItemRequest(101L, "Fries", BigDecimal.valueOf(4.00), 1)));
        var response = orderService.createOrder(request);
        assertThat(response.status()).isEqualTo("CONFIRMED");

        OutboxEvent snapshot = outboxEventRepository.findAll().stream()
                .filter(e -> "ORDER_ITEMS_SNAPSHOT".equals(e.getEventType()))
                .filter(e -> response.id().equals(e.getAggregateId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No ORDER_ITEMS_SNAPSHOT outbox row for order " + response.id()));

        // The row must be the full PlatformEventMessage envelope (the relay
        // re-publishes it verbatim) whose payload matches the survey contract.
        PlatformEventMessage envelope = PlatformEventMessage.fromJson(snapshot.getPayload());
        assertThat(envelope.eventType()).isEqualTo("ORDER_ITEMS_SNAPSHOT");
        JsonNode payload = objectMapper.readTree(envelope.payload());
        assertThat(payload.path("orderId").asLong()).isEqualTo(response.id());
        assertThat(payload.path("restaurantId").asLong()).isEqualTo(10L);
        assertThat(payload.path("orderedAt").asText()).isNotBlank();
        JsonNode items = payload.path("items");
        assertThat(items.isArray()).isTrue();
        assertThat(items.size()).isEqualTo(2);
        assertThat(items.get(0).path("menuItemId").asLong()).isEqualTo(100L);
        assertThat(items.get(0).path("name").asText()).isEqualTo("Burger");
        assertThat(items.get(0).path("quantity").asLong()).isEqualTo(2);
        assertThat(items.get(1).path("menuItemId").asLong()).isEqualTo(101L);
        assertThat(items.get(1).path("name").asText()).isEqualTo("Fries");
        assertThat(items.get(1).path("quantity").asLong()).isEqualTo(1);
    }

    @Test
    void asyncSagaEnabled_orderCreate_enqueuesPaymentRequestedInsteadOfCharging() throws Exception {
        when(restaurantClient.getMenuItems(any())).thenReturn(menuSnapshot());
        when(restaurantClient.reserveStock(any(), any()))
                .thenReturn(Mono.just(List.of(StockReservationLine.of(100L, "Burger", 1))));

        var service = context.getBean(OrderService.class);
        // The gate is a singleton bean the service reads per createOrder call,
        // so flipping it flips the branch (and the property-gate test below
        // restores it).
        var gate = context.getBean(com.bhukkad.order.OrderSagaProperties.class);
        gate.setEnabled(true);
        try {
            var response = service.createOrder(new CreateOrderRequest(
                    2L, 11L, List.of(new OrderItemRequest(100L, "Burger", BigDecimal.valueOf(12.50), 1))));

            assertThat(response.status()).isEqualTo(Order.STATUS_AWAITING_PAYMENT);
            org.mockito.Mockito.verify(paymentServiceClient, org.mockito.Mockito.never())
                    .charge(any(), any(), any(), any(), any(), any());

            OutboxEvent requested = outboxEventRepository.findAll().stream()
                    .filter(e -> "payment_requested".equals(e.getEventType()))
                    .filter(e -> response.id().equals(e.getAggregateId()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No payment_requested outbox row"));
            JsonNode payload = objectMapper.readTree(
                    PlatformEventMessage.fromJson(requested.getPayload()).payload());
            // W1-MONEY consumer contract — EXACT shape.
            assertThat(payload.path("orderId").asLong()).isEqualTo(response.id());
            assertThat(payload.path("customerId").asLong()).isEqualTo(2L);
            // Server-computed money is canonical scale-2 (createOrder re-prices
            // from the menu snapshot and setScale(2, HALF_UP) the total).
            assertThat(payload.path("amount").asText()).isEqualTo("12.50");
            assertThat(payload.path("currency").asText()).isEqualTo("INR");
            assertThat(payload.path("idempotencyKey").asText()).isEqualTo("ORDER-" + response.id());
        } finally {
            gate.setEnabled(false);
        }
    }

    @Test
    void compensation_enqueuesReverseStockReleaseEvent() throws Exception {
        when(restaurantClient.getMenuItems(any())).thenReturn(menuSnapshot());
        when(restaurantClient.reserveStock(any(), any()))
                .thenReturn(Mono.just(List.of(StockReservationLine.of(100L, "Burger", 2))));

        var gate = context.getBean(com.bhukkad.order.OrderSagaProperties.class);
        gate.setEnabled(true);
        try {
            var response = orderService.createOrder(new CreateOrderRequest(
                    3L, 12L, List.of(new OrderItemRequest(100L, "Burger", BigDecimal.valueOf(9.00), 2))));
            assertThat(response.status()).isEqualTo(Order.STATUS_AWAITING_PAYMENT);

            // Payment verdict = FAILED → compensate (as the consumer would).
            compensationService.compensatePaymentFailed(response.id(), "card_declined");

            Order order = orderRepository.findById(response.id()).orElseThrow();
            assertThat(order.getStatus()).isEqualTo(Order.STATUS_PAYMENT_FAILED);

            OutboxEvent release = outboxEventRepository.findAll().stream()
                    .filter(e -> "stock_release_requested".equals(e.getEventType()))
                    .filter(e -> response.id().equals(e.getAggregateId()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No stock_release_requested outbox row"));
            JsonNode payload = objectMapper.readTree(
                    PlatformEventMessage.fromJson(release.getPayload()).payload());
            assertThat(payload.path("orderId").asLong()).isEqualTo(response.id());
            assertThat(payload.path("lines").isArray()).isTrue();
            assertThat(payload.path("lines").get(0).path("menuItemId").asLong()).isEqualTo(100L);
            assertThat(payload.path("lines").get(0).path("quantity").asLong()).isEqualTo(2);
        } finally {
            gate.setEnabled(false);
        }
    }
}
