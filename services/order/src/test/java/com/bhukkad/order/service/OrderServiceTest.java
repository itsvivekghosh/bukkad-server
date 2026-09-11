package com.bhukkad.order.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.common.saga.SagaCoordinator;
import com.bhukkad.common.security.ServiceJwtAuthTokenProvider;
import com.bhukkad.order.api.CreateOrderRequest;
import com.bhukkad.order.api.OrderItemRequest;
import com.bhukkad.order.api.OrderResponse;
import com.bhukkad.order.api.RestaurantPricedItemResolver;
import com.bhukkad.order.client.PaymentServiceClient;
import com.bhukkad.order.client.RestaurantClient;
import com.bhukkad.order.domain.Order;
import com.bhukkad.order.domain.OrderItem;
import com.bhukkad.order.domain.OrderItemRepository;
import com.bhukkad.order.domain.OrderRepository;
import com.bhukkad.order.domain.OrderTimelineEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock private OrderRepository orderRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private OrderTimelineEventRepository timelineRepository;
    @Mock private SagaCoordinator sagaCoordinator;
    @Mock private OrderEventPublisher eventPublisher;
    @Mock private RestaurantClient restaurantClient;
    @Mock private RestaurantPricedItemResolver pricedItemResolver;
    @Mock private PaymentServiceClient paymentServiceClient;
    @Mock private ObjectProvider<ServiceJwtAuthTokenProvider> serviceJwtTokenProvider;
    /** Real defaults (async-saga OFF → synchronous path, the property-gate contract). */
    @org.mockito.Spy private com.bhukkad.order.OrderSagaProperties asyncSaga = new com.bhukkad.order.OrderSagaProperties();

    @InjectMocks private OrderService service;

    private CreateOrderRequest request(int quantity) {
        return new CreateOrderRequest(1L, 2L,
                List.of(new OrderItemRequest(100L, "Paneer", new BigDecimal("240.00"), quantity)));
    }

    /** Menu snapshot the restaurant service returns for the priced item. */
    private void menuPrices(BigDecimal serverPrice) {
        when(pricedItemResolver.resolveAll(anyCollection())).thenReturn(Map.of(
                100L, new RestaurantPricedItemResolver.PricedItem(100L, "Paneer", serverPrice)));
    }

    @Test
    void createOrder_calculatesTotalAndConfirms() {
        menuPrices(new BigDecimal("240.00"));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setId(7L);
            return o;
        });
        lenient().when(orderItemRepository.findByOrderId(7L)).thenReturn(List.of());
        lenient().when(sagaCoordinator.executeSaga(anyString(), anyString(), anyString(), anyList()))
                .thenReturn(null);

        OrderResponse response = service.createOrder(request(2));

        assertThat(response.id()).isEqualTo(7L);
        assertThat(response.totalAmount()).isEqualByComparingTo("480.00");
        assertThat(response.status()).isEqualTo(Order.STATUS_CONFIRMED);
        verify(eventPublisher).orderCreated(7L, 1L, 2L);
        verify(eventPublisher).orderStatusChanged(7L, Order.STATUS_CONFIRMED);
    }

    @Test
    void createOrder_clientSendsWrongPrice_storesServerPrice() {
        // Tampered checkout: the client claims 999.00/unit; the restaurant
        // menu says 240.00. The stored order, items and events must carry the
        // SERVER price only.
        menuPrices(new BigDecimal("240.00"));
        CreateOrderRequest tampered = new CreateOrderRequest(1L, 2L,
                List.of(new OrderItemRequest(100L, "Paneer", new BigDecimal("999.00"), 2)));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setId(7L);
            return o;
        });
        lenient().when(orderItemRepository.findByOrderId(7L)).thenReturn(List.of());
        lenient().when(sagaCoordinator.executeSaga(anyString(), anyString(), anyString(), anyList()))
                .thenReturn(null);

        OrderResponse response = service.createOrder(tampered);

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository, org.mockito.Mockito.atLeastOnce()).save(orderCaptor.capture());
        assertThat(orderCaptor.getValue().getTotalAmount()).isEqualByComparingTo("480.00");
        assertThat(response.totalAmount()).isEqualByComparingTo("480.00");

        ArgumentCaptor<OrderItem> itemCaptor = ArgumentCaptor.forClass(OrderItem.class);
        verify(orderItemRepository).save(itemCaptor.capture());
        assertThat(itemCaptor.getValue().getUnitPrice()).isEqualByComparingTo("240.00");
        assertThat(itemCaptor.getValue().getItemName()).isEqualTo("Paneer");
    }

    @Test
    void createOrder_unavailableMenuItem_rejectsBeforePersisting() {
        // The restaurant omits the id (nonexistent/unavailable/inactive) —
        // the resolver's existing convention rejects the order and NOTHING
        // is written.
        when(pricedItemResolver.resolveAll(anyCollection()))
                .thenThrow(new BusinessException("Menu item is temporarily unavailable: 100"));

        assertThatThrownBy(() -> service.createOrder(request(1)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("unavailable");
        verifyNoInteractions(orderRepository, orderItemRepository, eventPublisher);
    }

    @Test
    void createOrder_rePricedLines_driveEventsAndSagaPayload() {
        // Line names/prices travel from the menu snapshot into the stock
        // reservation and the snapshot event (client labels are ignored).
        menuPrices(new BigDecimal("240.00"));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setId(7L);
            return o;
        });
        lenient().when(orderItemRepository.findByOrderId(7L)).thenReturn(List.of());
        lenient().when(restaurantClient.reserveStock(anyList(), any()))
                .thenReturn(reactor.core.publisher.Mono.just(List.of()));
        lenient().when(paymentServiceClient.charge(any(), any(), any(), any(), any(), any()))
                .thenReturn(reactor.core.publisher.Mono.just(
                        new com.bhukkad.order.client.dto.ChargeResponse(9L, "CHARGED")));
        lenient().when(sagaCoordinator.executeSaga(anyString(), anyString(), anyString(), anyList()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    List<com.bhukkad.common.saga.SagaStepDefinition> steps =
                            (List<com.bhukkad.common.saga.SagaStepDefinition>) inv.getArgument(3);
                    steps.forEach(s -> s.action().execute(s.name(), "{}"));
                    return null;
                });

        service.createOrder(request(2));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<com.bhukkad.order.client.dto.StockReservationLine>> linesCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(restaurantClient).reserveStock(linesCaptor.capture(), any());
        assertThat(linesCaptor.getValue().get(0).menuItemName()).isEqualTo("Paneer");
    }

    @Test
    void createOrder_emptyItems_throws() {
        CreateOrderRequest empty = new CreateOrderRequest(1L, 2L, List.of());
        assertThatThrownBy(() -> service.createOrder(empty))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("at least one item");
    }

    @Test
    void createOrder_zeroQuantity_throws() {
        assertThatThrownBy(() -> service.createOrder(request(0)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("quantity");
    }

    @Test
    void cancelOrder_setsCancelledAndPublishes() {
        Order order = new Order();
        order.setId(5L);
        order.setStatus(Order.STATUS_CREATED);
        when(orderRepository.findById(5L)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        service.cancelOrder(5L);

        assertThat(order.getStatus()).isEqualTo(Order.STATUS_CANCELLED);
        verify(eventPublisher).orderStatusChanged(5L, Order.STATUS_CANCELLED);
    }

    @Test
    void cancelOrder_deliveredOrder_throws() {
        Order delivered = new Order();
        delivered.setId(5L);
        delivered.setStatus(Order.STATUS_DELIVERED);
        when(orderRepository.findById(5L)).thenReturn(Optional.of(delivered));

        assertThatThrownBy(() -> service.cancelOrder(5L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("delivered");
    }

    @Test
    void getOrder_unknown_throws() {
        when(orderRepository.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getOrder(9L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("9");
    }
}
