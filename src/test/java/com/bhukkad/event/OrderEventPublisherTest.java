package com.bhukkad.event;

import com.bhukkad.entity.Customer;
import com.bhukkad.entity.DeliveryAgent;
import com.bhukkad.entity.MenuItem;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.OrderItem;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.common.outbox.OutboxEventService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderEventPublisherTest {

    @Mock
    private OutboxEventService outboxEventService;

    @InjectMocks
    private OrderEventPublisher orderEventPublisher;

    private Order baseOrder() {
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
        return order;
    }

    @Test
    void publishStatusChange_enqueuesOutboxEvent() {
        Order order = baseOrder();

        orderEventPublisher.publishStatusChange(order, Order.OrderStatus.PLACED);

        verify(outboxEventService).enqueue(eq("ORDER_STATUS_CHANGED"), eq(99L), any());
    }

    @Test
    void publishStatusChange_withAgent_includesAgentId() {
        Order order = baseOrder();
        DeliveryAgent agent = new DeliveryAgent();
        agent.setId(4L);
        order.setDeliveryAgent(agent);

        orderEventPublisher.publishStatusChange(order, Order.OrderStatus.PLACED);

        verify(outboxEventService).enqueue(eq("ORDER_STATUS_CHANGED"), eq(99L), any());
    }

    @Test
    void publishCreated_enqueuesOutboxEvent() {
        Order order = baseOrder();

        orderEventPublisher.publishCreated(order);

        verify(outboxEventService).enqueue(eq("ORDER_CREATED"), eq(99L), any());
    }

    @Test
    void publishItemsSnapshot_nullOrder_skipped() {
        orderEventPublisher.publishItemsSnapshot(null);

        verify(outboxEventService, never()).enqueue(any(), any(), any());
    }

    @Test
    void publishItemsSnapshot_nullOrderId_skipped() {
        Order order = baseOrder();
        order.setId(null);

        orderEventPublisher.publishItemsSnapshot(order);

        verify(outboxEventService, never()).enqueue(any(), any(), any());
    }

    @Test
    void publishItemsSnapshot_nullOrderItems_skipped() {
        Order order = baseOrder();
        order.setOrderItems(null);

        orderEventPublisher.publishItemsSnapshot(order);

        verify(outboxEventService, never()).enqueue(any(), any(), any());
    }

    @Test
    void publishItemsSnapshot_emptyItems_skipped() {
        Order order = baseOrder();
        order.setOrderItems(List.of());

        orderEventPublisher.publishItemsSnapshot(order);

        verify(outboxEventService, never()).enqueue(any(), any(), any());
    }

    @Test
    void publishItemsSnapshot_allItemsWithoutMenuItems_skipped() {
        Order order = baseOrder();
        OrderItem item = new OrderItem();
        item.setMenuItem(null);
        item.setQuantity(2);
        order.setOrderItems(List.of(item));

        orderEventPublisher.publishItemsSnapshot(order);

        verify(outboxEventService, never()).enqueue(any(), any(), any());
    }

    @Test
    void publishItemsSnapshot_withItems_enqueuesSnapshot() {
        Order order = baseOrder();
        MenuItem menuItem = new MenuItem();
        menuItem.setId(7L);
        menuItem.setName("Biryani");
        OrderItem item = new OrderItem();
        item.setMenuItem(menuItem);
        item.setQuantity(2);
        order.setOrderItems(List.of(item));

        orderEventPublisher.publishItemsSnapshot(order);

        verify(outboxEventService).enqueue(eq("ORDER_ITEMS_SNAPSHOT"), eq(99L), any());
    }

    @Test
    void publishAgentAssigned_enqueuesOutboxEvent() {
        Order order = baseOrder();
        DeliveryAgent agent = new DeliveryAgent();
        agent.setId(4L);
        order.setDeliveryAgent(agent);
        order.setStatus(Order.OrderStatus.READY_FOR_PICKUP);

        orderEventPublisher.publishAgentAssigned(order);

        verify(outboxEventService).enqueue(eq("ORDER_AGENT_ASSIGNED"), eq(99L), any());
    }

    @Test
    void publishAgentAssigned_withoutAgent_enqueuesWithNullAgent() {
        Order order = baseOrder();
        order.setStatus(Order.OrderStatus.READY_FOR_PICKUP);

        orderEventPublisher.publishAgentAssigned(order);

        verify(outboxEventService).enqueue(eq("ORDER_AGENT_ASSIGNED"), eq(99L), any());
    }
}
