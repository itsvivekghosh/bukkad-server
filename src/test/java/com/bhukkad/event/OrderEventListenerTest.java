package com.bhukkad.event;

import com.bhukkad.entity.Order;
import com.bhukkad.live.OrderLiveUpdateBroadcaster;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderEventListenerTest {

    @Mock
    private OrderLiveUpdateBroadcaster liveUpdateBroadcaster;
    @Mock
    private com.bhukkad.service.NotificationService notificationService;
    @Mock
    private com.bhukkad.config.ExternalEventsProperties externalEventsProperties;

    @InjectMocks
    private OrderEventListener listener;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(externalEventsProperties.isKafkaEnabled()).thenReturn(false);
    }

    @Test
    void onOrderStatusChanged_broadcastsLiveUpdate() {
        OrderStatusChangedEvent event = new OrderStatusChangedEvent(
                1L,
                "ORD-ABC123",
                2L,
                3L,
                4L,
                Order.OrderStatus.PLACED,
                Order.OrderStatus.CONFIRMED,
                LocalDateTime.now());

        listener.onOrderStatusChanged(event);

        verify(liveUpdateBroadcaster).broadcastStatusChange(event);
    }

    @Test
    void onOrderCreated_broadcastsLiveUpdate() {
        OrderCreatedEvent event = new OrderCreatedEvent(
                1L, "ORD-NEW", 2L, 3L, LocalDateTime.now());

        listener.onOrderCreated(event);

        verify(liveUpdateBroadcaster).broadcastOrderCreated(event);
    }

    @Test
    void onOrderAgentAssigned_broadcastsLiveUpdate() {
        OrderAgentAssignedEvent event = new OrderAgentAssignedEvent(
                1L, "ORD-ABC", 2L, 3L, 4L,
                Order.OrderStatus.READY_FOR_PICKUP, LocalDateTime.now());

        listener.onOrderAgentAssigned(event);

        verify(liveUpdateBroadcaster).broadcastAgentAssigned(event);
    }
}
