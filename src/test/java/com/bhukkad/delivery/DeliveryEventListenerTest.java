package com.bhukkad.delivery;

import com.bhukkad.entity.Customer;
import com.bhukkad.entity.DeliveryAgent;
import com.bhukkad.entity.Order;
import com.bhukkad.event.OrderSettledEvent;
import com.bhukkad.repository.DeliveryAgentRepository;
import com.bhukkad.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Delivery-side consumer of the ORDER_SETTLED outbox event: the rider earning
 * is recorded inside the delivery domain, which owns the aggregate.
 */
@ExtendWith(MockitoExtension.class)
class DeliveryEventListenerTest {

    @Mock private RiderEarningService riderEarningService;
    @Mock private OrderRepository orderRepository;
    @Mock private DeliveryAgentRepository deliveryAgentRepository;

    @InjectMocks
    private DeliveryEventListener listener;

    @Test
    void onOrderSettled_recordsEarningForAgent() {
        Order order = new Order();
        order.setId(10L);
        order.setTipAmount(20.0);
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));
        DeliveryAgent agent = new DeliveryAgent();
        agent.setId(3L);
        when(deliveryAgentRepository.findById(3L)).thenReturn(Optional.of(agent));

        listener.onOrderSettled(new OrderSettledEvent(
                10L, "ORD-10", 9L, 3L, 20.0, LocalDateTime.now()));

        verify(riderEarningService).recordDeliveryEarning(order, agent);
    }

    @Test
    void onOrderSettled_withoutAgent_doesNothing() {
        listener.onOrderSettled(new OrderSettledEvent(
                10L, "ORD-10", 9L, null, null, LocalDateTime.now()));

        verify(orderRepository, never()).findById(any());
        verify(riderEarningService, never()).recordDeliveryEarning(any(), any());
    }

    @Test
    void onOrderSettled_missingOrder_isIgnored() {
        when(orderRepository.findById(99L)).thenReturn(java.util.Optional.empty());

        listener.onOrderSettled(new OrderSettledEvent(
                99L, "ORD-99", 9L, 3L, null, LocalDateTime.now()));

        verify(riderEarningService, never()).recordDeliveryEarning(any(), any());
    }

    @Test
    void onOrderSettled_missingAgent_isIgnored() {
        Order order = new Order();
        order.setId(10L);
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));
        when(deliveryAgentRepository.findById(3L)).thenReturn(java.util.Optional.empty());

        listener.onOrderSettled(new OrderSettledEvent(
                10L, "ORD-10", 9L, 3L, 20.0, LocalDateTime.now()));

        verify(riderEarningService, never()).recordDeliveryEarning(any(), any());
    }
}
