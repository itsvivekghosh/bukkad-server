package com.bhukkad.order;

import com.bhukkad.delivery.OrderEtaService;
import com.bhukkad.entity.Order;
import com.bhukkad.event.OrderEventPublisher;
import com.bhukkad.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScheduledOrderProcessorTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderEventPublisher orderEventPublisher;

    @Mock
    private OrderEtaService orderEtaService;

    @InjectMocks
    private ScheduledOrderProcessor processor;

    @Test
    void dispatchDueOrders_processesAllDueOrders() {
        Order dueOrder1 = order(1L, "ORD-1", LocalDateTime.now().minusMinutes(5));
        Order dueOrder2 = order(2L, "ORD-2", LocalDateTime.now().minusMinutes(1));
        when(orderRepository.findByStatusAndScheduledAtLessThanEqual(any(Order.OrderStatus.class), any(LocalDateTime.class)))
                .thenReturn(List.of(dueOrder1, dueOrder2));

        processor.dispatchDueOrders();

        assertEquals(Order.OrderStatus.PLACED, dueOrder1.getStatus());
        assertEquals(Order.OrderStatus.PLACED, dueOrder2.getStatus());
        // The merged poller clears scheduledAt (absorbed from the former
        // ScheduledOrderScheduler) so a dispatched order is never re-selected.
        assertNull(dueOrder1.getScheduledAt());
        assertNull(dueOrder2.getScheduledAt());
        verify(orderEtaService).applyLiveEta(dueOrder1);
        verify(orderEtaService).applyLiveEta(dueOrder2);
        verify(orderRepository).save(dueOrder1);
        verify(orderRepository).save(dueOrder2);
        verify(orderEventPublisher).publishStatusChange(dueOrder1, Order.OrderStatus.SCHEDULED);
        verify(orderEventPublisher).publishStatusChange(dueOrder2, Order.OrderStatus.SCHEDULED);
    }

    @Test
    void dispatchDueOrders_noDueOrders() {
        when(orderRepository.findByStatusAndScheduledAtLessThanEqual(any(Order.OrderStatus.class), any(LocalDateTime.class)))
                .thenReturn(List.of());

        processor.dispatchDueOrders();

        verify(orderEventPublisher, never()).publishStatusChange(any(), any());
    }

    @Test
    void dispatchDueOrders_failureOnOneOrder_doesNotBlockTheRest() {
        Order failingOrder = order(1L, "ORD-1", LocalDateTime.now().minusMinutes(5));
        Order healthyOrder = order(2L, "ORD-2", LocalDateTime.now().minusMinutes(1));
        when(orderRepository.findByStatusAndScheduledAtLessThanEqual(any(Order.OrderStatus.class), any(LocalDateTime.class)))
                .thenReturn(List.of(failingOrder, healthyOrder));
        doThrow(new RuntimeException("eta boom")).when(orderEtaService).applyLiveEta(failingOrder);

        processor.dispatchDueOrders();

        // The failing order is skipped; the healthy one still dispatches.
        verify(orderEventPublisher, never()).publishStatusChange(eq(failingOrder), any());
        verify(orderEventPublisher).publishStatusChange(healthyOrder, Order.OrderStatus.SCHEDULED);
        verify(orderRepository, times(1)).save(healthyOrder);
    }

    private Order order(long id, String orderNumber, LocalDateTime scheduledAt) {
        try {
            Order order = new Order();
            setField(order, "id", id);
            setField(order, "orderNumber", orderNumber);
            order.setStatus(Order.OrderStatus.SCHEDULED);
            order.setScheduledAt(scheduledAt);
            return order;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
