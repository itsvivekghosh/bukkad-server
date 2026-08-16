package com.bhukkad.order;

import com.bhukkad.entity.Order;
import com.bhukkad.event.OrderEventPublisher;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScheduledOrderSchedulerTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderService orderService;

    @Mock
    private OrderEventPublisher orderEventPublisher;

    @InjectMocks
    private ScheduledOrderScheduler scheduler;

    @Test
    void processScheduledOrders_noDueOrders_doesNothing() {
        when(orderRepository.findByStatusAndScheduledAtLessThanEqual(
                eq(Order.OrderStatus.SCHEDULED), any(LocalDateTime.class)))
                .thenReturn(List.of());

        scheduler.processScheduledOrders();

        verify(orderRepository, never()).save(any(Order.class));
        verify(orderEventPublisher, never()).publishStatusChange(any(), any());
    }

    @Test
    void processScheduledOrders_dueOrders_transitionsToPlaced() {
        Order order = new Order();
        order.setId(1L);
        order.setStatus(Order.OrderStatus.SCHEDULED);
        order.setScheduledAt(LocalDateTime.now().minusMinutes(5));

        when(orderRepository.findByStatusAndScheduledAtLessThanEqual(
                eq(Order.OrderStatus.SCHEDULED), any(LocalDateTime.class)))
                .thenReturn(List.of(order));
        when(orderRepository.save(order)).thenReturn(order);

        scheduler.processScheduledOrders();

        assertEquals(Order.OrderStatus.PLACED, order.getStatus());
        assertNull(order.getScheduledAt());
        verify(orderRepository).save(order);
        verify(orderEventPublisher).publishStatusChange(order, Order.OrderStatus.SCHEDULED);
    }

    @Test
    void processScheduledOrders_exception_continuesProcessing() {
        Order order1 = new Order();
        order1.setId(1L);
        order1.setStatus(Order.OrderStatus.SCHEDULED);

        Order order2 = new Order();
        order2.setId(2L);
        order2.setStatus(Order.OrderStatus.SCHEDULED);

        when(orderRepository.findByStatusAndScheduledAtLessThanEqual(
                eq(Order.OrderStatus.SCHEDULED), any(LocalDateTime.class)))
                .thenReturn(List.of(order1, order2));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        scheduler.processScheduledOrders();

        verify(orderRepository, times(2)).save(any(Order.class));
        verify(orderEventPublisher, times(2)).publishStatusChange(any(), any());
    }
}