package com.bhukkad.live;

import com.bhukkad.dto.response.OrderLiveUpdate;
import com.bhukkad.delivery.OrderEtaService;
import com.bhukkad.entity.Order;
import com.bhukkad.event.OrderAgentAssignedEvent;
import com.bhukkad.event.OrderCreatedEvent;
import com.bhukkad.event.OrderStatusChangedEvent;
import com.bhukkad.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderLiveUpdateBroadcasterTest {

    @Mock
    private OrderLiveRelay orderLiveRelay;

    @Mock
    private OrderLiveReplayStore orderLiveReplayStore;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderEtaService orderEtaService;

    @InjectMocks
    private OrderLiveUpdateBroadcaster broadcaster;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        // baseUpdate() only queries the order when present; lenient so the stub
        // does not trip strict mode for the RIDER_LOCATION path (which bypasses it).
        org.mockito.Mockito.lenient()
                .when(orderRepository.findByIdWithDetails(anyLong())).thenReturn(Optional.empty());
    }

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

    @Test
    void broadcastRiderLocation_publishesToRelay() {
        when(orderLiveReplayStore.nextEventId()).thenReturn(103L);

        broadcaster.broadcastRiderLocation(1L, 2L, 3L, 4L, 12.97, 77.59);

        var captor = org.mockito.ArgumentCaptor.forClass(OrderLiveUpdate.class);
        verify(orderLiveRelay).publish(captor.capture());
        OrderLiveUpdate update = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(update.getEventType())
                .isEqualTo(OrderLiveUpdate.EventType.RIDER_LOCATION);
        org.assertj.core.api.Assertions.assertThat(update.getLatitude()).isEqualTo(12.97);
        org.assertj.core.api.Assertions.assertThat(update.getLongitude()).isEqualTo(77.59);
        org.assertj.core.api.Assertions.assertThat(update.getOrderId()).isEqualTo(1L);
    }
}
