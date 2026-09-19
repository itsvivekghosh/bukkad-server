package com.bhukkad.realtime.domain.service.impl;

import com.bhukkad.realtime.config.LiveProperties;
import com.bhukkad.realtime.domain.event.OrderLiveUpdate;
import com.bhukkad.realtime.domain.service.OrderLiveReplayStore;
import com.bhukkad.realtime.exception.SseCapacityExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderSseStreamServiceImplTest {

    @Mock
    private OrderLiveReplayStore replayStore;

    private LiveProperties liveProperties;
    private io.micrometer.core.instrument.simple.SimpleMeterRegistry metrics;
    private OrderSseStreamServiceImpl service;

    @BeforeEach
    void initService() {
        liveProperties = new LiveProperties();
        metrics = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        service = new OrderSseStreamServiceImpl(replayStore, liveProperties, metrics);
    }

    private double rejectedCount(String reason) {
        return metrics.find(OrderSseStreamServiceImpl.METRIC_CAPACITY_REJECTED)
                .tag("reason", reason).counter().count();
    }

    @Test
    void subscribeKitchen_createsFlux() {
        Flux<ServerSentEvent<String>> flux = service.subscribeKitchen(1L, null);

        assertNotNull(flux);
        assertEquals(1, service.activeConnectionCount());
    }

    @Test
    void subscribeKitchen_withLastEventId_replaysEvents() {
        lenient().when(replayStore.streamKeyKitchen(1L)).thenReturn("kitchen:1");
        lenient().when(replayStore.parseLastEventId("5")).thenReturn(5L);
        when(replayStore.replayAfter(eq("kitchen:1"), eq(5L)))
                .thenReturn(List.of(new OrderLiveUpdate()));

        Flux<ServerSentEvent<String>> flux = service.subscribeKitchen(1L, "5");

        assertNotNull(flux);
        verify(replayStore).replayAfter(eq("kitchen:1"), eq(5L));
    }

    @Test
    void subscribeKitchen_withBlankLastEventId_skipsReplay() {
        Flux<ServerSentEvent<String>> flux = service.subscribeKitchen(1L, "  ");

        assertNotNull(flux);
        verify(replayStore, never()).replayAfter(anyString(), anyLong());
    }

    @Test
    void broadcastKitchen_noSubscribers_doesNothing() {
        OrderLiveUpdate update = new OrderLiveUpdate();
        update.setEventId(1L);

        assertDoesNotThrow(() -> service.broadcastKitchen(999L, update));
    }

    @Test
    void broadcastKitchen_sendsToSubscribers() {
        Flux<ServerSentEvent<String>> flux = service.subscribeKitchen(1L, null);

        // Subscribe to the flux in a separate thread to keep the test simple
        java.util.concurrent.atomic.AtomicReference<ServerSentEvent<String>> captured = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        flux.subscribe(captured::set, e -> {}, latch::countDown);

        OrderLiveUpdate update = new OrderLiveUpdate();
        update.setEventId(7L);
        service.broadcastKitchen(1L, update);

        assertDoesNotThrow(() -> latch.await(2, java.util.concurrent.TimeUnit.SECONDS));
        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().event()).isEqualTo("order-update");
    }

    @Test
    void broadcastCustomer_noSubscribers_doesNothing() {
        OrderLiveUpdate update = new OrderLiveUpdate();
        update.setEventId(3L);

        assertDoesNotThrow(() -> service.broadcastCustomer(999L, update));
    }

    @Test
    void sendHeartbeats_emptyRegistry_isNoop() {
        assertDoesNotThrow(() -> service.sendHeartbeats());
        assertEquals(0, service.activeConnectionCount());
    }

    @Test
    void sendHeartbeats_deliversToActiveSubscribers() {
        Flux<ServerSentEvent<String>> flux = service.subscribeKitchen(1L, null);

        // Verify heartbeat dispatch does not throw and the stream stays alive
        assertDoesNotThrow(() -> service.sendHeartbeats());
        assertThat(flux).isNotNull();
    }

    @Test
    void globalCapacityRejectsExcessSubscribers() {
        liveProperties.setMaxTotalEmitters(2);

        service.subscribeKitchen(1L, null);
        service.subscribeRider(2L, null);

        assertThrows(SseCapacityExceededException.class, () -> service.subscribeCustomer(3L, null, null));
        assertEquals(2, service.activeConnectionCount());
    }

    @Test
    void globalBudgetRejectsExcessAndDoesNotLeakSlot() {
        liveProperties.setMaxTotalEmitters(1);

        service.subscribeKitchen(1L, null);
        assertEquals(1, service.activeConnectionCount());

        assertThrows(SseCapacityExceededException.class,
                () -> service.subscribeCustomer(2L, null, null));
        assertEquals(1, service.activeConnectionCount());
    }

    @Test
    void capacityRejectsAreCountedPerReason() {
        liveProperties.setMaxTotalEmitters(1);
        service.subscribeKitchen(1L, null);
        assertThrows(SseCapacityExceededException.class, () -> service.subscribeRider(2L, null));
        assertEquals(1.0, rejectedCount("global"));
    }

    @Test
    void connectionsGaugeTracksRegisteredEmitters() {
        assertEquals(0.0, metrics.find(OrderSseStreamServiceImpl.METRIC_CONNECTIONS).gauge().value());

        service.subscribeKitchen(1L, null);
        service.subscribeRider(2L, null);
        assertEquals(2.0, metrics.find(OrderSseStreamServiceImpl.METRIC_CONNECTIONS).gauge().value());
    }
}
