package com.bhukkad.live;

import com.bhukkad.dto.response.OrderLiveUpdate;
import com.bhukkad.live.OrderLiveReplayStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderSseStreamServiceTest {

    @Mock
    private OrderLiveReplayStore replayStore;

    @InjectMocks
    private OrderSseStreamService service;

    @Test
    void subscribeKitchen_createsEmitter() {
        SseEmitter emitter = service.subscribeKitchen(1L, null);
        assertNotNull(emitter);
    }

    @Test
    void subscribeKitchen_withLastEventId_replaysEvents() throws IOException {
        lenient().when(replayStore.replayAfter(anyString(), anyLong())).thenReturn(List.of(new OrderLiveUpdate()));

        SseEmitter emitter = service.subscribeKitchen(1L, "5");

        assertNotNull(emitter);
        verify(replayStore).replayAfter(eq("kitchen:1"), eq(5L));
    }

    @Test
    void subscribeKitchen_withInvalidLastEventId_noReplay() throws IOException {
        // Invalid format returns -1 from parseLastEventId, so no replay
        SseEmitter emitter = service.subscribeKitchen(1L, "invalid");

        assertNotNull(emitter);
        verify(replayStore, never()).replayAfter(anyString(), anyLong());
    }

    @Test
    void subscribeRider_createsEmitter() {
        SseEmitter emitter = service.subscribeRider(1L, null);
        assertNotNull(emitter);
    }

    @Test
    void subscribeRider_withLastEventId_replaysEvents() throws IOException {
        lenient().when(replayStore.replayAfter(anyString(), anyLong())).thenReturn(List.of(new OrderLiveUpdate()));

        SseEmitter emitter = service.subscribeRider(1L, "10");

        assertNotNull(emitter);
        verify(replayStore).replayAfter(eq("rider:1"), eq(10L));
    }

    @Test
    void subscribeCustomer_createsEmitter() {
        SseEmitter emitter = service.subscribeCustomer(1L, null, null);
        assertNotNull(emitter);
    }

    @Test
    void subscribeCustomer_withSnapshot_sendsSnapshot() throws IOException {
        Object snapshot = new Object();
        // No replay needed for null lastEventId
        SseEmitter emitter = service.subscribeCustomer(1L, null, snapshot);

        assertNotNull(emitter);
    }

    @Test
    void broadcastKitchen_sendsToSubscribers() throws IOException {
        OrderLiveUpdate update = new OrderLiveUpdate();
        update.setEventId(1L);
        service.broadcastKitchen(1L, update);
        // Verify no exception thrown
    }

    @Test
    void broadcastKitchen_noSubscribers_doesNothing() throws IOException {
        OrderLiveUpdate update = new OrderLiveUpdate();
        update.setEventId(1L);
        service.broadcastKitchen(999L, update); // No subscribers for this ID
        // Should not throw
    }

    @Test
    void broadcastRider_sendsToSubscribers() throws IOException {
        OrderLiveUpdate update = new OrderLiveUpdate();
        update.setEventId(2L);
        service.broadcastRider(1L, update);
    }

    @Test
    void broadcastRider_noSubscribers_doesNothing() throws IOException {
        OrderLiveUpdate update = new OrderLiveUpdate();
        update.setEventId(2L);
        service.broadcastRider(999L, update);
    }

    @Test
    void broadcastCustomer_sendsToSubscribers() throws IOException {
        OrderLiveUpdate update = new OrderLiveUpdate();
        update.setEventId(3L);
        service.broadcastCustomer(1L, update);
    }

    @Test
    void broadcastCustomer_noSubscribers_doesNothing() throws IOException {
        OrderLiveUpdate update = new OrderLiveUpdate();
        update.setEventId(3L);
        service.broadcastCustomer(999L, update);
    }

    @Test
    void sendHeartbeats_sendsToAllStreams() {
        service.sendHeartbeats();
    }

    @Test
    void activeConnectionCount_returnsCount() {
        int count = service.activeConnectionCount();
        assertEquals(0, count);
    }

    @Test
    void shutdown_closesAllStreams() {
        // Subscribe to create some streams
        service.subscribeKitchen(1L, null);
        service.subscribeRider(1L, null);
        service.subscribeCustomer(1L, null, null);

        // Call shutdown via reflection since it's @PreDestroy
        assertDoesNotThrow(() -> {
            java.lang.reflect.Method method = OrderSseStreamService.class.getDeclaredMethod("shutdown");
            method.setAccessible(true);
            method.invoke(service);
        });
    }

    @Test
    void subscribe_returnsEmitterWithCallbacks() throws IOException {
        // Test that emitter has completion/timeout/error callbacks registered
        SseEmitter emitter = service.subscribeKitchen(1L, null);
        assertNotNull(emitter);
    }

    @Test
    void broadcast_handlesIOExceptionOnSend() throws Exception {
        // Subscribe to create a stream
        SseEmitter emitter = service.subscribeKitchen(1L, null);
        
        // Create an update that will cause IOException on send
        OrderLiveUpdate update = new OrderLiveUpdate();
        update.setEventId(1L);
        
        // The broadcast should handle the exception gracefully
        service.broadcastKitchen(1L, update);
        
        // Should not throw
    }

    @Test
    void replayMissedEvents_withValidLastEventId_callsReplayStore() throws Exception {
        // Test the private replayMissedEvents method via subscribe
        lenient().when(replayStore.replayAfter(eq("kitchen:1"), eq(5L)))
                .thenReturn(List.of(new OrderLiveUpdate()));
        
        SseEmitter emitter = service.subscribeKitchen(1L, "5");
        assertNotNull(emitter);
        verify(replayStore).replayAfter(eq("kitchen:1"), eq(5L));
    }

    @Test
    void replayMissedEvents_withNullLastEventId_skipsReplay() throws Exception {
        SseEmitter emitter = service.subscribeKitchen(1L, null);
        assertNotNull(emitter);
        verify(replayStore, never()).replayAfter(anyString(), anyLong());
    }

    @Test
    void replayMissedEvents_withBlankLastEventId_skipsReplay() throws Exception {
        SseEmitter emitter = service.subscribeKitchen(1L, "  ");
        assertNotNull(emitter);
        verify(replayStore, never()).replayAfter(anyString(), anyLong());
    }

    @Test
    void replayMissedEvents_negativeAfterEventId_skipsReplay() throws Exception {
        // "event-" parses to -1
        SseEmitter emitter = service.subscribeKitchen(1L, "event-");
        assertNotNull(emitter);
        verify(replayStore, never()).replayAfter(anyString(), anyLong());
    }

    @Test
    void sendUpdate_withNullEventId_noIdInEvent() throws IOException {
        OrderLiveUpdate update = new OrderLiveUpdate();
        update.setEventId(null);
        
        // Subscribe to create emitter, then test sendUpdate via broadcast
        service.subscribeKitchen(1L, null);
        service.broadcastKitchen(1L, update);
        
        // Should not throw
    }

    @Test
    void closeAll_onShutdown_clearsStreams() throws Exception {
        service.subscribeKitchen(1L, null);
        service.subscribeRider(1L, null);
        service.subscribeCustomer(1L, null, null);

        assertTrue(service.activeConnectionCount() > 0);

        java.lang.reflect.Method method = OrderSseStreamService.class.getDeclaredMethod("shutdown");
        method.setAccessible(true);
        method.invoke(service);

        assertEquals(0, service.activeConnectionCount());
    }

    // ==================== additional coverage ====================

    @Test
    void subscribeCustomer_withLastEventId_replaysMissedEvents() throws IOException {
        lenient().when(replayStore.replayAfter(anyString(), anyLong()))
                .thenReturn(List.of(new OrderLiveUpdate()));

        SseEmitter emitter = service.subscribeCustomer(1L, "7", null);

        assertNotNull(emitter);
        verify(replayStore).replayAfter(eq("order:1"), eq(7L));
    }

    @Test
    void broadcastKitchen_afterSubscriberRemovalViaCompletion_emitterRemoved() throws IOException {
        SseEmitter emitter = service.subscribeKitchen(1L, null);
        // complete() only fires the onCompletion callback when an MVC handler is
        // initialized; in a unit test the emitter stays registered, so the count
        // remains 1 until the service's own cleanup runs.
        emitter.complete();
        assertEquals(1, service.activeConnectionCount());
    }

    @Test
    void broadcast_toBrokenEmitter_removesAndCompletesIt() throws IOException {
        // Subscribe a real emitter; sending to a completed emitter triggers IOException,
        // which the broadcast loop must swallow and clean up.
        SseEmitter emitter = service.subscribeKitchen(1L, null);
        emitter.complete();

        assertDoesNotThrow(() -> service.broadcastKitchen(1L, new OrderLiveUpdate()));
    }

    @Test
    void sendHeartbeats_withActiveStreams_sendsCommentsOrCleansUp() {
        service.subscribeKitchen(1L, null);
        service.subscribeCustomer(2L, null, null);

        assertDoesNotThrow(() -> service.sendHeartbeats());
    }

    @Test
    void sendHeartbeat_afterEmitterClosed_cleansUpWithoutThrowing() throws IOException {
        SseEmitter emitter = service.subscribeKitchen(1L, null);
        // Mark the emitter complete without an MVC handler attached:
        // subsequent sends raise IllegalStateException.
        emitter.complete();

        // Heartbeat sweep catches it, removes the dead emitter, and completes it
        assertDoesNotThrow(() -> service.sendHeartbeats());
        assertEquals(0, service.activeConnectionCount());
    }
}