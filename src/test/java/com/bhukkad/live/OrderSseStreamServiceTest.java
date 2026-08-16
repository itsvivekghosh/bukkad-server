package com.bhukkad.live;

import com.bhukkad.dto.response.OrderLiveUpdate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderSseStreamServiceTest {

    @Mock
    private OrderLiveReplayStore replayStore;

    private OrderSseStreamService service;

    @BeforeEach
    void setUp() {
        service = new OrderSseStreamService(replayStore);
    }

    @Test
    void subscribeKitchen_returnsConnectedEmitter() {
        SseEmitter emitter = service.subscribeKitchen(10L, null);
        assertNotNull(emitter);
    }

    @Test
    void subscribeRider_returnsConnectedEmitter() {
        SseEmitter emitter = service.subscribeRider(7L, null);
        assertNotNull(emitter);
    }

    @Test
    void subscribeCustomer_replaysMissedEvents() {
        OrderLiveUpdate update = orderUpdate(15L);
        when(replayStore.replayAfter("order:1", 10L)).thenReturn(List.of(update));

        SseEmitter emitter = service.subscribeCustomer(1L, "10", null);

        assertNotNull(emitter);
        verify(replayStore).replayAfter("order:1", 10L);
    }

    @Test
    void broadcastKitchen_doesNotFailWhenNoSubscribers() {
        service.broadcastKitchen(10L, orderUpdate(1L));
    }

    @Test
    void broadcastKitchen_deliversToSubscribers() {
        service.subscribeKitchen(10L, null);
        assertDoesNotThrow(() -> service.broadcastKitchen(10L, orderUpdate(2L)));
    }

    @Test
    void broadcastRider_deliversToSubscribers() {
        service.subscribeRider(7L, null);
        assertDoesNotThrow(() -> service.broadcastRider(7L, orderUpdate(3L)));
    }

    @Test
    void broadcastCustomer_deliversToSubscribers() {
        service.subscribeCustomer(1L, null, null);
        assertDoesNotThrow(() -> service.broadcastCustomer(1L, orderUpdate(4L)));
    }

    @Test
    void sendHeartbeats_doesNotFailWhenNoSubscribers() {
        service.sendHeartbeats();
    }

    @Test
    void sendHeartbeats_sendsToActiveSubscribers() {
        service.subscribeKitchen(10L, null);
        service.subscribeRider(7L, null);
        assertDoesNotThrow(() -> service.sendHeartbeats());
    }

    @Test
    void shutdown_closesActiveStreams() {
        service.subscribeKitchen(10L, null);
        service.subscribeRider(7L, null);
        assertDoesNotThrow(() -> service.shutdown());
    }

    private static OrderLiveUpdate orderUpdate(long eventId) {
        return OrderLiveUpdate.builder()
                .eventId(eventId)
                .eventType(OrderLiveUpdate.EventType.ORDER_CREATED)
                .orderId(1L)
                .restaurantId(10L)
                .changedAt(LocalDateTime.now())
                .build();
    }
}
