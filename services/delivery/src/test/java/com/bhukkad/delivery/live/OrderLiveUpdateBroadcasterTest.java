package com.bhukkad.delivery.live;

import com.bhukkad.delivery.api.EtaPort;
import com.bhukkad.delivery.dto.response.OrderLiveUpdate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Broadcaster: stamp a monotonic event id, record for replay before relaying,
 * and enrich rider-location broadcasts with the live ETA from the port.
 */
@ExtendWith(MockitoExtension.class)
class OrderLiveUpdateBroadcasterTest {

    @Mock private OrderLiveRelay orderLiveRelay;
    @Mock private OrderLiveReplayStore orderLiveReplayStore;
    @Mock private EtaPort etaPort;

    @InjectMocks private OrderLiveUpdateBroadcaster broadcaster;

    @Test
    void broadcastRiderLocation_recordsThenPublishesWithStampedEventId() {
        when(orderLiveReplayStore.nextEventId()).thenReturn(42L);
        OrderLiveUpdate update = capturePublished();

        assertThat(update.getEventType()).isEqualTo(OrderLiveUpdate.EventType.RIDER_LOCATION);
        assertThat(update.getEventId()).isEqualTo(42L);
        assertThat(update.getOrderId()).isEqualTo(7L);
        assertThat(update.getCustomerId()).isEqualTo(11L);
        assertThat(update.getRestaurantId()).isEqualTo(9L);
        assertThat(update.getDeliveryAgentId()).isEqualTo(3L);
        assertThat(update.getOrderNumber()).isEqualTo("BK-7");
        assertThat(update.getChangedAt()).isNotNull();

        var ordered = inOrder(orderLiveReplayStore, orderLiveRelay);
        ordered.verify(orderLiveReplayStore).record(any(OrderLiveUpdate.class));
        ordered.verify(orderLiveRelay).publish(any(OrderLiveUpdate.class));
    }

    @Test
    void broadcastRiderLocation_carriesExplicitEtaWhenProvided() {
        LocalDateTime etaAt = LocalDateTime.of(2026, 9, 5, 12, 30);

        when(orderLiveReplayStore.nextEventId()).thenReturn(1L);
        broadcaster.broadcastRiderLocation(7L, 11L, 9L, 3L, 12.9, 77.6, "BK-7", 8, etaAt);

        ArgumentCaptor<OrderLiveUpdate> captor = ArgumentCaptor.forClass(OrderLiveUpdate.class);
        verify(orderLiveRelay).publish(captor.capture());
        assertThat(captor.getValue().getLiveEtaMinutes()).isEqualTo(8);
        assertThat(captor.getValue().getLiveEtaAt()).isEqualTo(etaAt);
    }

    @Test
    void broadcastRiderLocation_shortOverloadLeavesEtaEmpty() {
        when(orderLiveReplayStore.nextEventId()).thenReturn(1L);

        broadcaster.broadcastRiderLocation(7L, 11L, 9L, 3L, 12.9, 77.6);

        ArgumentCaptor<OrderLiveUpdate> captor = ArgumentCaptor.forClass(OrderLiveUpdate.class);
        verify(orderLiveRelay).publish(captor.capture());
        assertThat(captor.getValue().getLiveEtaMinutes()).isNull();
        assertThat(captor.getValue().getOrderNumber()).isNull();
    }

    private OrderLiveUpdate capturePublished() {
        broadcaster.broadcastRiderLocation(7L, 11L, 9L, 3L, 12.9, 77.6, "BK-7", null, null);
        ArgumentCaptor<OrderLiveUpdate> captor = ArgumentCaptor.forClass(OrderLiveUpdate.class);
        verify(orderLiveRelay).publish(captor.capture());
        return captor.getValue();
    }
}
