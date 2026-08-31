package com.bhukkad.order.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.common.saga.SagaCoordinator;
import com.bhukkad.order.api.CreateOrderRequest;
import com.bhukkad.order.api.OrderItemRequest;
import com.bhukkad.order.api.OrderResponse;
import com.bhukkad.order.domain.Order;
import com.bhukkad.order.domain.OrderItemRepository;
import com.bhukkad.order.domain.OrderRepository;
import com.bhukkad.order.domain.OrderTimelineEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock private OrderRepository orderRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private OrderTimelineEventRepository timelineRepository;
    @Mock private SagaCoordinator sagaCoordinator;
    @Mock private OrderEventPublisher eventPublisher;

    @InjectMocks private OrderService service;

    private CreateOrderRequest request(int quantity) {
        return new CreateOrderRequest(1L, 2L,
                List.of(new OrderItemRequest(100L, "Paneer", new BigDecimal("240.00"), quantity)));
    }

    @Test
    void createOrder_calculatesTotalAndConfirms() {
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
