package com.bhukkad.realtime.domain.service.impl;

import com.bhukkad.realtime.config.LiveProperties;
import com.bhukkad.realtime.domain.event.OrderLiveUpdate;
import com.bhukkad.realtime.exception.SseCapacityExceededException;
import com.bhukkad.realtime.domain.service.OrderLiveReplayStore;
import com.bhukkad.realtime.domain.service.impl.OrderSseStreamServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
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
        Executor syncExecutor = Runnable::run;
        liveProperties = new LiveProperties();
        metrics = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        service = new OrderSseStreamServiceImpl(replayStore, syncExecutor, liveProperties, metrics);
    }

    private double rejectedCount(String reason) {
        return metrics.find(OrderSseStreamServiceImpl.METRIC_CAPACITY_REJECTED)
                .tag("reason", reason).counter().count();
    }

    @Test
    void activeConnectionCount_isZeroWhenEmpty() {
        assertEquals(0, service.activeConnectionCount());
    }

    @Test
    void subscribeKitchen_createsEmitter() {
        SseEmitter emitter = service.subscribeKitchen(1L, null);

        assertNotNull(emitter);
        assertEquals(1, service.activeConnectionCount());
    }

    @Test
    void subscribeKitchen_withLastEventId_replaysEvents() {
        lenient().when(replayStore.streamKeyKitchen(1L)).thenReturn("kitchen:1");
        lenient().when(replayStore.parseLastEventId("5")).thenReturn(5L);
        when(replayStore.replayAfter(eq("kitchen:1"), eq(5L)))
                .thenReturn(List.of(new OrderLiveUpdate()));

        SseEmitter emitter = service.subscribeKitchen(1L, "5");

        assertNotNull(emitter);
        verify(replayStore).replayAfter(eq("kitchen:1"), eq(5L));
    }

    @Test
    void subscribeKitchen_withBlankLastEventId_skipsReplay() {
        SseEmitter emitter = service.subscribeKitchen(1L, "  ");

        assertNotNull(emitter);
        verify(replayStore, never()).replayAfter(anyString(), anyLong());
    }

    @Test
    void broadcastKitchen_noSubscribers_doesNothing() {
        OrderLiveUpdate update = new OrderLiveUpdate();
        update.setEventId(1L);

        assertDoesNotThrow(() -> service.broadcastKitchen(999L, update));
    }

    @Test
    void broadcastKitchen_sendsToSubscribers() throws Exception {
        SseEmitter emitter = mockEmitter();
        registerEmitter("kitchenStreams", 1L, emitter);

        OrderLiveUpdate update = new OrderLiveUpdate();
        update.setEventId(7L);

        service.broadcastKitchen(1L, update);

        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        assertEquals(1, service.activeConnectionCount());
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
    void sendHeartbeats_deliversToActiveSubscribers() throws Exception {
        SseEmitter emitter = mockEmitter();
        registerEmitter("kitchenStreams", 1L, emitter);

        service.sendHeartbeats();

        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        assertEquals(1, service.activeConnectionCount());
    }

    @Test
    void broadcast_brokenEmitter_isRemovedAndCompleted() throws Exception {
        SseEmitter emitter = mockEmitter();
        registerEmitter("kitchenStreams", 1L, emitter);
        assertEquals(1, service.activeConnectionCount());

        doThrow(new IOException("broken pipe")).when(emitter)
                .send(any(SseEmitter.SseEventBuilder.class));

        assertDoesNotThrow(() -> service.broadcastKitchen(1L, new OrderLiveUpdate()));

        assertEquals(0, service.activeConnectionCount());
        verify(emitter).complete();
    }

    @Test
    void perStreamCapacityRejectsExcessSubscribers() {
        liveProperties.setMaxEmittersPerStream(2);

        service.subscribeKitchen(1L, null);
        service.subscribeKitchen(1L, null);

        assertThrows(SseCapacityExceededException.class, () -> service.subscribeKitchen(1L, null));
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
        liveProperties.setMaxEmittersPerStream(1);
        service.subscribeKitchen(1L, null);
        assertThrows(SseCapacityExceededException.class, () -> service.subscribeKitchen(1L, null));
        assertEquals(1.0, rejectedCount("stream"));

        liveProperties.setMaxTotalEmitters(1);
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

    @Test
    void dispatchSaturationEvictsEmitterNeverRunsInline() {
        Executor rejecting = task -> {
            throw new java.util.concurrent.RejectedExecutionException("saturated");
        };
        OrderSseStreamServiceImpl saturated = new OrderSseStreamServiceImpl(
                replayStore, rejecting, liveProperties, metrics);
        saturated.subscribeKitchen(1L, null);

        saturated.broadcastKitchen(1L, new OrderLiveUpdate());

        // Abort-policy semantics: the emitter is dropped + counted, the send
        // never falls back to the caller thread.
        assertEquals(0, saturated.activeConnectionCount());
        assertEquals(0, saturated.indexedEmitterCount());
        assertEquals(1.0, rejectedCount("dispatch"));
    }

    @Test
    void sendFailureEvictsIndexedEmitterInO1() {
        SseEmitter emitter = service.subscribeKitchen(4L, null);
        emitter.complete(); // any later send raises IllegalStateException

        service.broadcastKitchen(4L, new OrderLiveUpdate());

        // Removal resolved through the emitter index: stream entry and the
        // index entry both gone, no scan needed.
        assertEquals(0, service.activeConnectionCount());
        assertEquals(0, service.indexedEmitterCount());
    }

    @Test
    void shutdown_completesEmittersAndClearsRegistries() throws Exception {
        SseEmitter kitchen = mockEmitter();
        SseEmitter rider = mockEmitter();
        SseEmitter customer = mockEmitter();
        registerEmitter("kitchenStreams", 1L, kitchen);
        registerEmitter("riderStreams", 2L, rider);
        registerEmitter("customerStreams", 3L, customer);
        assertEquals(3, service.activeConnectionCount());

        invokeShutdown();

        assertEquals(0, service.activeConnectionCount());
        verify(kitchen).complete();
        verify(rider).complete();
        verify(customer).complete();
    }

    @Test
    void shutdown_completingEmitterThatThrows_isSwallowed() throws Exception {
        SseEmitter emitter = mockEmitter();
        registerEmitter("kitchenStreams", 1L, emitter);
        doThrow(new IllegalStateException("already closed")).when(emitter).complete();

        invokeShutdown();

        assertEquals(0, service.activeConnectionCount());
    }

    private SseEmitter mockEmitter() {
        return mock(SseEmitter.class);
    }

    private void registerEmitter(String fieldName, Long key, SseEmitter emitter) throws Exception {
        java.lang.reflect.Field field = OrderSseStreamServiceImpl.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>> streams =
                (ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>>) field.get(service);
        streams.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(emitter);
    }

    private void invokeShutdown() throws Exception {
        java.lang.reflect.Method method = OrderSseStreamServiceImpl.class.getDeclaredMethod("shutdown");
        method.setAccessible(true);
        method.invoke(service);
    }
}
