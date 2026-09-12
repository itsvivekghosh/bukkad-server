package com.bhukkad.order;

import com.bhukkad.common.outbox.OutboxEvent;
import com.bhukkad.common.outbox.OutboxEventRepository;
import com.bhukkad.order.api.dto.request.CreateOrderRequest;
import com.bhukkad.order.api.dto.request.OrderItemRequest;
import com.bhukkad.order.infrastructure.client.PaymentServiceClient;
import com.bhukkad.order.infrastructure.client.RestaurantClient;
import com.bhukkad.order.client.dto.ChargeResponse;
import com.bhukkad.order.client.dto.StockReservationLine;
import com.bhukkad.order.domain.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-gate contract (feature #3): with {@code app.order.async-saga.enabled=false}
 * (the shipped default) order-create behaves EXACTLY like the batch A-2025
 * synchronous saga — reserve + charge inline, order CONFIRMED in the request
 * thread, no {@code payment_requested} outbox row. The gate flipping to true
 * is covered by {@code OrderSagaEventContractPostgresIntegrationTest}.
 */
@SpringBootTest(properties = "app.order.async-saga.enabled=false")
class AsyncSagaPropertyGatePostgresIntegrationTest extends AbstractOrderPostgresTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @MockBean
    private RestaurantClient restaurantClient;

    @MockBean
    private PaymentServiceClient paymentServiceClient;

    /**
     * Menu snapshot the mocked restaurant service returns for item 100. The
     * price mirrors OrderSagaEventContractPostgresIntegrationTest (which
     * shares this cached Spring context and therefore the 60 s resolver
     * cache — item ids must resolve to the SAME price in both classes).
     */
    private static Mono<List<java.util.Map<String, Object>>> menuSnapshot() {
        java.util.Map<String, Object> burger = new java.util.LinkedHashMap<>();
        burger.put("id", 100L);
        burger.put("restaurantId", 20L);
        burger.put("name", "Burger");
        burger.put("price", new BigDecimal("12.50"));
        return Mono.just(List.of(burger));
    }

    @Test
    void gateOff_createOrder_runsSynchronousSagaAndNeverRequestsPaymentViaOutbox() {
        when(restaurantClient.getMenuItems(any())).thenReturn(menuSnapshot());
        when(restaurantClient.reserveStock(any(), any()))
                .thenReturn(Mono.just(List.of(StockReservationLine.of(100L, "Burger", 1))));
        when(paymentServiceClient.charge(any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(new ChargeResponse(9L, "CHARGED")));

        var response = orderService.createOrder(new CreateOrderRequest(
                5L, 20L, List.of(new OrderItemRequest(100L, "Burger", BigDecimal.valueOf(8.00), 3))));

        // Today's behavior, byte for byte:
        assertThat(response.status()).isEqualTo("CONFIRMED");
        verify(paymentServiceClient).charge(any(), any(), any(), any(), any(), any());
        assertThat(outboxEventRepository.findAll().stream())
                .noneMatch(e -> "payment_requested".equals(e.getEventType()));
        assertThat(outboxEventRepository.findAll().stream())
                .noneMatch(e -> "stock_release_requested".equals(e.getEventType()));
    }

    @Test
    void gateOff_failedCharge_stillCompensatesSynchronouslyAndCancels() {
        when(restaurantClient.getMenuItems(any())).thenReturn(menuSnapshot());
        when(restaurantClient.reserveStock(any(), any()))
                .thenReturn(Mono.just(List.of(StockReservationLine.of(100L, "Burger", 1))));
        when(restaurantClient.releaseStock(any(), any()))
                .thenReturn(Mono.just(List.of()));
        when(paymentServiceClient.charge(any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.error(new RuntimeException("gateway down")));

        var response = orderService.createOrder(new CreateOrderRequest(
                6L, 21L, List.of(new OrderItemRequest(100L, "Burger", BigDecimal.valueOf(8.00), 1))));

        assertThat(response.status()).isEqualTo("CANCELLED");
        verify(restaurantClient).releaseStock(any(), any());
        // The synchronous path compensates via the client, never via outbox.
        assertThat(outboxEventRepository.findAll().stream()
                .map(OutboxEvent::getEventType))
                .doesNotContain("stock_release_requested");
    }
}
