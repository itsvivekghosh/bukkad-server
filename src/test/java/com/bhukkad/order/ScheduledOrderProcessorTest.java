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
import static org.mockito.ArgumentMatchers.any;
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
        Order dueOrder1 = order(1L, "ORD-1");
        Order dueOrder2 = order(2L, "ORD-2");
        when(orderRepository.findByStatusAndScheduledAtLessThanEqual(any(Order.OrderStatus.class), any(LocalDateTime.class)))
                .thenReturn(List.of(dueOrder1, dueOrder2));

        processor.dispatchDueOrders();

        assertEquals(Order.OrderStatus.PLACED, dueOrder1.getStatus());
        assertEquals(Order.OrderStatus.PLACED, dueOrder2.getStatus());
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

        verify(orderEventPublisher, org.mockito.Mockito.never())
                .publishStatusChange(any(), any());
    }

    private Order order(long id, String orderNumber) {
        try {
            Order order = new Order();
            setField(order, "id", id);
            setField(order, "orderNumber", orderNumber);
            order.setStatus(Order.OrderStatus.SCHEDULED);
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
