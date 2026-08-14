package com.bhukkad.live;

import com.bhukkad.dto.response.OrderLiveUpdate;
import com.bhukkad.entity.Order;
import com.bhukkad.event.OrderAgentAssignedEvent;
import com.bhukkad.event.OrderCreatedEvent;
import com.bhukkad.event.OrderStatusChangedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderLiveUpdateBroadcasterTest {

    @Mock
    private OrderLiveRelay orderLiveRelay;

    @Mock
    private OrderLiveReplayStore orderLiveReplayStore;

    @InjectMocks
    private OrderLiveUpdateBroadcaster broadcaster;

    @Test
    void broadcastStatusChange_publishesToRelay() {
        when(orderLiveReplayStore.nextEventId()).thenReturn(100L);
        OrderStatusChangedEvent event = new OrderStatusChangedEvent(
                1L, "ORD-1", 2L, 3L, 4L,
                Order.OrderStatus.READY_FOR_PICKUP, Order.OrderStatus.OUT_FOR_DELIVERY,
                LocalDateTime.now());

        broadcaster.broadcastStatusChange(event);

        verify(orderLiveReplayStore).record(any(OrderLiveUpdate.class));
        verify(orderLiveRelay).publish(any(OrderLiveUpdate.class));
    }

    @Test
    void broadcastOrderCreated_publishesToRelay() {
        when(orderLiveReplayStore.nextEventId()).thenReturn(101L);
        OrderCreatedEvent event = new OrderCreatedEvent(
                1L, "ORD-NEW", 2L, 3L, LocalDateTime.now());

        broadcaster.broadcastOrderCreated(event);

        verify(orderLiveReplayStore).record(any(OrderLiveUpdate.class));
        verify(orderLiveRelay).publish(any(OrderLiveUpdate.class));
    }

    @Test
    void broadcastAgentAssigned_publishesToRelay() {
        when(orderLiveReplayStore.nextEventId()).thenReturn(102L);
        OrderAgentAssignedEvent event = new OrderAgentAssignedEvent(
                1L, "ORD-1", 2L, 3L, 4L,
                Order.OrderStatus.READY_FOR_PICKUP, LocalDateTime.now());

        broadcaster.broadcastAgentAssigned(event);

        verify(orderLiveReplayStore).record(any(OrderLiveUpdate.class));
        verify(orderLiveRelay).publish(any(OrderLiveUpdate.class));
    }
}
