package com.bhukkad.event;

import com.bhukkad.entity.Customer;
import com.bhukkad.entity.DeliveryAgent;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.outbox.OutboxEventService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderEventPublisherTest {

    @Mock
    private OutboxEventService outboxEventService;

    @InjectMocks
    private OrderEventPublisher orderEventPublisher;

    @Test
    void publishStatusChange_enqueuesOutboxEvent() {
        Customer customer = new Customer();
        customer.setId(1L);
        Restaurant restaurant = new Restaurant();
        restaurant.setId(10L);
        Order order = new Order();
        order.setId(99L);
        order.setOrderNumber("ORD-TEST01");
        order.setCustomer(customer);
        order.setRestaurant(restaurant);
        order.setStatus(Order.OrderStatus.CONFIRMED);

        orderEventPublisher.publishStatusChange(order, Order.OrderStatus.PLACED);

        verify(outboxEventService).enqueue(eq("ORDER_STATUS_CHANGED"), eq(99L), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void publishCreated_enqueuesOutboxEvent() {
        Customer customer = new Customer();
        customer.setId(1L);
        Restaurant restaurant = new Restaurant();
        restaurant.setId(10L);
        Order order = new Order();
        order.setId(99L);
        order.setOrderNumber("ORD-NEW");
        order.setCustomer(customer);
        order.setRestaurant(restaurant);

        orderEventPublisher.publishCreated(order);

        verify(outboxEventService).enqueue(eq("ORDER_CREATED"), eq(99L), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void publishAgentAssigned_enqueuesOutboxEvent() {
        Customer customer = new Customer();
        customer.setId(1L);
        Restaurant restaurant = new Restaurant();
        restaurant.setId(10L);
        DeliveryAgent agent = new DeliveryAgent();
        agent.setId(4L);
        Order order = new Order();
        order.setId(99L);
        order.setOrderNumber("ORD-ASSIGN");
        order.setCustomer(customer);
        order.setRestaurant(restaurant);
        order.setDeliveryAgent(agent);
        order.setStatus(Order.OrderStatus.READY_FOR_PICKUP);

        orderEventPublisher.publishAgentAssigned(order);

        verify(outboxEventService).enqueue(eq("ORDER_AGENT_ASSIGNED"), eq(99L), org.mockito.ArgumentMatchers.any());
    }
}
