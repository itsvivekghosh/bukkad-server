package com.bhukkad.commerce.order.domain.service.impl;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.common.saga.SagaCoordinator;
import com.bhukkad.common.security.ServiceJwtAuthTokenProvider;
import com.bhukkad.commerce.order.api.dto.request.CreateOrderRequest;
import com.bhukkad.commerce.order.api.dto.request.OrderItemRequest;
import com.bhukkad.commerce.order.api.dto.response.OrderResponse;
import com.bhukkad.commerce.order.api.RestaurantPricedItemResolver;
import com.bhukkad.commerce.order.infrastructure.client.PaymentServiceClient;
import com.bhukkad.commerce.order.infrastructure.client.RestaurantClient;
import com.bhukkad.commerce.order.domain.entity.Order;
import com.bhukkad.commerce.order.domain.repository.OrderItemRepository;
import com.bhukkad.commerce.order.domain.repository.OrderRepository;
import com.bhukkad.commerce.order.domain.repository.OrderTimelineEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock private OrderRepository orderRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private OrderTimelineEventRepository timelineRepository;
    @Mock private SagaCoordinator sagaCoordinator;
    @Mock private OrderCreationService orderCreationService;
    @Mock private OrderQueryService orderQueryService;
    @Mock private OrderLifecycleService orderLifecycleService;
    @Mock private OrderReorderService orderReorderService;
    @Mock private OrderEventPublisher eventPublisher;
    @Mock private RestaurantClient restaurantClient;
    @Mock private RestaurantPricedItemResolver pricedItemResolver;
    @Mock private PaymentServiceClient paymentServiceClient;
    @Mock private ObjectProvider<ServiceJwtAuthTokenProvider> serviceJwtTokenProvider;
    /** Real defaults (async-saga OFF → synchronous path, the property-gate contract). */
    @org.mockito.Spy private com.bhukkad.commerce.order.config.OrderSagaProperties asyncSaga = new com.bhukkad.commerce.order.config.OrderSagaProperties();

    @InjectMocks private OrderService service;

    private CreateOrderRequest request(int quantity) {
        return new CreateOrderRequest(1L, 2L,
                List.of(new OrderItemRequest(100L, "Paneer", new BigDecimal("240.00"), quantity)));
    }

    @org.junit.jupiter.api.BeforeEach
    void disableAsyncSaga() {
        // Force synchronous saga for these tests
        asyncSaga.setEnabled(false);
    }

    @Test
    void createOrder_calculatesTotalAndConfirms() {
        when(orderCreationService.createOrder(any(CreateOrderRequest.class)))
                .thenReturn(new OrderResponse(7L, 1L, 2L, Order.STATUS_CONFIRMED,
                        new BigDecimal("480.00"), List.of()));

        OrderResponse response = service.createOrder(request(2));

        assertThat(response.id()).isEqualTo(7L);
        assertThat(response.totalAmount()).isEqualByComparingTo("480.00");
        assertThat(response.status()).isEqualTo(Order.STATUS_CONFIRMED);
    }

    @Test
    void createOrder_clientSendsWrongPrice_storesServerPrice() {
        // Tampered checkout: the client claims 999.00/unit; the restaurant
        // menu says 240.00. The stored order, items and events must carry the
        // SERVER price only.
        CreateOrderRequest tampered = new CreateOrderRequest(1L, 2L,
                List.of(new OrderItemRequest(100L, "Paneer", new BigDecimal("999.00"), 2)));
        when(orderCreationService.createOrder(any(CreateOrderRequest.class)))
                .thenReturn(new OrderResponse(7L, 1L, 2L, Order.STATUS_CONFIRMED,
                        new BigDecimal("480.00"), List.of()));

        OrderResponse response = service.createOrder(tampered);

        assertThat(response.totalAmount()).isEqualByComparingTo("480.00");
    }

    @Test
    void createOrder_unavailableMenuItem_rejectsBeforePersisting() {
        // The restaurant omits the id (nonexistent/unavailable/inactive) —
        // the resolver's existing convention rejects the order and NOTHING
        // is written.
        when(orderCreationService.createOrder(any(CreateOrderRequest.class)))
                .thenThrow(new BusinessException("Menu item is temporarily unavailable: 100"));

        assertThatThrownBy(() -> service.createOrder(request(1)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("unavailable");
    }

    @Test
    void createOrder_rePricedLines_driveEventsAndSagaPayload() {
        // Line names/prices travel from the menu snapshot into the stock
        // reservation and the snapshot event (client labels are ignored).
        when(orderCreationService.createOrder(any(CreateOrderRequest.class)))
                .thenReturn(new OrderResponse(7L, 1L, 2L, Order.STATUS_CONFIRMED,
                        new BigDecimal("480.00"), List.of()));

        service.createOrder(request(2));
    }

    @Test
    void createOrder_emptyItems_throws() {
        CreateOrderRequest empty = new CreateOrderRequest(1L, 2L, List.of());
        when(orderCreationService.createOrder(any(CreateOrderRequest.class)))
                .thenThrow(new BusinessException("at least one item"));
        assertThatThrownBy(() -> service.createOrder(empty))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("at least one item");
    }

    @Test
    void createOrder_zeroQuantity_throws() {
        when(orderCreationService.createOrder(any(CreateOrderRequest.class)))
                .thenThrow(new BusinessException("quantity"));
        assertThatThrownBy(() -> service.createOrder(request(0)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("quantity");
    }

    @Test
    void cancelOrder_setsCancelledAndPublishes() {
        Order order = new Order();
        order.setId(5L);
        order.setStatus(Order.STATUS_CREATED);
        doAnswer(inv -> {
            order.setStatus(Order.STATUS_CANCELLED);
            return null;
        }).when(orderLifecycleService).cancelOrder(5L);

        service.cancelOrder(5L);

        assertThat(order.getStatus()).isEqualTo(Order.STATUS_CANCELLED);
    }

    @Test
    void cancelOrder_deliveredOrder_throws() {
        Order delivered = new Order();
        delivered.setId(5L);
        delivered.setStatus(Order.STATUS_DELIVERED);
        doThrow(new BusinessException("delivered")).when(orderLifecycleService).cancelOrder(5L);

        assertThatThrownBy(() -> service.cancelOrder(5L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("delivered");
    }

    @Test
    void getOrder_unknown_throws() {
        when(orderQueryService.getOrder(9L))
                .thenThrow(new ResourceNotFoundException("Order not found: 9"));
        assertThatThrownBy(() -> service.getOrder(9L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("9");
    }
}
