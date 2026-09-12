package com.bhukkad.realtime.domain.service.impl;

import com.bhukkad.realtime.domain.event.OrderLiveUpdate;
import com.bhukkad.realtime.domain.service.OrderLiveRelay;
import com.bhukkad.realtime.domain.service.OrderSseStreamService;
import com.bhukkad.realtime.domain.service.impl.OrderLiveSseBridge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Bridge contract: wired exactly once with a channel-aware sink, and each
 * channel routes to exactly one stream kind (an update fanned to three
 * channels must not triple-deliver per stream).
 */
@ExtendWith(MockitoExtension.class)
class OrderLiveSseBridgeTest {

    @Mock private OrderLiveRelay relay;
    @Mock private OrderSseStreamService streamService;

    @Test
    void wiresChannelAwareSinkOnReady() {
        OrderLiveSseBridge bridge = new OrderLiveSseBridge(relay, streamService);
        bridge.wire();

        ArgumentCaptor<BiConsumer<String, OrderLiveUpdate>> captor =
                ArgumentCaptor.forClass(BiConsumer.class);
        verify(relay).subscribeAll(captor.capture());
        assertThat(captor.getValue()).isNotNull();

        OrderLiveUpdate update = new OrderLiveUpdate();
        update.setOrderId(42L);
        update.setRestaurantId(7L);
        captor.getValue().accept("live:channel:order:42", update);
        verify(streamService).broadcastCustomer(42L, update);

        captor.getValue().accept("live:channel:kitchen:7", update);
        verify(streamService).broadcastKitchen(7L, update);
    }

    @Test
    void riderChannel_routesToStreamService() {
        OrderLiveSseBridge bridge = new OrderLiveSseBridge(relay, streamService);
        bridge.wire();
        ArgumentCaptor<BiConsumer<String, OrderLiveUpdate>> captor =
                ArgumentCaptor.forClass(BiConsumer.class);
        verify(relay).subscribeAll(captor.capture());

        OrderLiveUpdate update = new OrderLiveUpdate();
        update.setDeliveryAgentId(3L);
        captor.getValue().accept("live:channel:rider:3", update);
        verify(streamService).broadcastRider(3L, update);
    }

    @Test
    void unknownChannel_toleratedQuietly() {
        OrderLiveSseBridge bridge = new OrderLiveSseBridge(relay, streamService);
        bridge.wire();
        ArgumentCaptor<BiConsumer<String, OrderLiveUpdate>> captor =
                ArgumentCaptor.forClass(BiConsumer.class);
        verify(relay).subscribeAll(captor.capture());
        OrderLiveUpdate update = new OrderLiveUpdate();
        update.setOrderId(1L);

        captor.getValue().accept("live:channel:someone-elses-stream", update);

        verifyNoInteractions(streamService);
    }
}
