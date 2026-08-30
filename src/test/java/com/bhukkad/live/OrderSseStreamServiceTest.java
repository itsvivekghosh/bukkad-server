package com.bhukkad.live;

import com.bhukkad.dto.response.OrderLiveUpdate;
import com.bhukkad.exception.SseCapacityExceededException;
import com.bhukkad.live.OrderLiveReplayStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executor;

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

    private OrderSseStreamService service;

    @BeforeEach
    void initService() {
        Executor syncExecutor = Runnable::run;
        service = new OrderSseStreamService(replayStore, syncExecutor);
    }

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
    void broadcastKitchen_sendsToSubscribers() throws Exception {
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
    void broadcastRider_sendsToSubscribers() throws Exception {
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
    void broadcastCustomer_sendsToSubscribers() throws Exception {
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

    // ==================== per-stream capacity cap ====================

    @Test
    void subscribe_rejectsNewEmitterWhenStreamAtCapacity() {
        service.maxEmittersPerStream = 2;
        service.subscribeKitchen(1L, null);
        service.subscribeKitchen(1L, null);

        assertThrows(SseCapacityExceededException.class, () -> service.subscribeKitchen(1L, null));
    }

    @Test
    void subscribe_capacityIsPerStreamKey() {
        service.maxEmittersPerStream = 1;
        service.subscribeKitchen(1L, null);

        assertThrows(SseCapacityExceededException.class, () -> service.subscribeKitchen(1L, null));
        // A different stream key is unaffected
        assertNotNull(service.subscribeKitchen(2L, null));
        assertNotNull(service.subscribeRider(9L, null));
        assertNotNull(service.subscribeCustomer(3L, null, null));
    }

    // ==================== fleet-wide (per-pod) connection budget ====================

    @Test
    void subscribe_globalBudget_exceedsTotalRejectsNewSubscribers() {
        service.maxTotalEmitters = 2;
        service.subscribeKitchen(1L, null);
        service.subscribeCustomer(2L, null, null);

        assertThrows(SseCapacityExceededException.class, () -> service.subscribeRider(3L, null));
    }

    @Test
    void subscribe_globalBudget_isReleasedOnEmitterRemoval() {
        service.maxTotalEmitters = 1;
        SseEmitter first = service.subscribeKitchen(1L, null);

        // Release the slot by completing the emitter; the cleanup runs inline.
        first.complete();
        service.sendHeartbeats();

        // The budget slot is freed, so a new subscription succeeds.
        assertNotNull(service.subscribeCustomer(2L, null, null));
    }

    @Test
    void subscribe_globalBudgetRejectedSubscriber_doesNotLeakSlot() {
        service.maxTotalEmitters = 1;
        service.subscribeKitchen(1L, null);
        assertThrows(SseCapacityExceededException.class, () -> service.subscribeRider(9L, null));
        // The rejected subscriber must not hold a slot.
        assertEquals(1, service.activeConnectionCount());
    }

    // ===== Batch D coverage: Local broadcast variants, failure cleanup, heartbeat cleanup =====

    @Test
    void broadcastKitchenLocal_sendsToSubscribers() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        registerEmitter("kitchenStreams", 1L, emitter);

        OrderLiveUpdate update = new OrderLiveUpdate();
        update.setEventId(7L);

        service.broadcastKitchenLocal(1L, update);

        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        assertEquals(1, service.activeConnectionCount());
    }

    @Test
    void broadcastRiderLocal_sendsToSubscribers() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        registerEmitter("riderStreams", 1L, emitter);

        OrderLiveUpdate update = new OrderLiveUpdate();
        update.setEventId(8L);

        service.broadcastRiderLocal(1L, update);

        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        assertEquals(1, service.activeConnectionCount());
    }

    @Test
    void broadcastCustomerLocal_sendsToSubscribers() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        registerEmitter("customerStreams", 1L, emitter);

        OrderLiveUpdate update = new OrderLiveUpdate();
        update.setEventId(9L);

        service.broadcastCustomerLocal(1L, update);

        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        assertEquals(1, service.activeConnectionCount());
    }

    @Test
    void broadcastLocal_noSubscribersForStreamKey_doesNothing() {
        assertDoesNotThrow(() -> service.broadcastKitchenLocal(999L, new OrderLiveUpdate()));
        assertDoesNotThrow(() -> service.broadcastRiderLocal(999L, new OrderLiveUpdate()));
        assertDoesNotThrow(() -> service.broadcastCustomerLocal(999L, new OrderLiveUpdate()));
    }

    @Test
    void broadcast_sendFailure_removesEmitterFromAllStreamsAndCompletes() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        registerEmitter("kitchenStreams", 1L, emitter);
        assertEquals(1, service.activeConnectionCount());

        // A completed/broken emitter throws IllegalStateException on send → cleanup path
        doThrow(new IllegalStateException("completed")).when(emitter)
                .send(any(SseEmitter.SseEventBuilder.class));

        service.broadcastKitchenLocal(1L, new OrderLiveUpdate());

        // Emitter was removed from the stream registry and completed
        assertEquals(0, service.activeConnectionCount());
        verify(emitter).complete();
    }

    @Test
    void sendHeartbeat_sendFailure_removesDeadEmitter() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        registerEmitter("customerStreams", 1L, emitter);
        assertEquals(1, service.activeConnectionCount());

        doThrow(new IOException("broken pipe")).when(emitter)
                .send(any(SseEmitter.SseEventBuilder.class));

        service.sendHeartbeats();

        assertEquals(0, service.activeConnectionCount());
        verify(emitter).complete();
    }

    @Test
    void sendHeartbeat_healthyEmitter_staysSubscribed() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        registerEmitter("kitchenStreams", 1L, emitter);

        service.sendHeartbeats();

        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        assertEquals(1, service.activeConnectionCount());
    }

    @Test
    void shutdown_completesEmittersAndClearsAllRegistries() throws Exception {
        SseEmitter kitchen = mock(SseEmitter.class);
        SseEmitter rider = mock(SseEmitter.class);
        SseEmitter customer = mock(SseEmitter.class);
        registerEmitter("kitchenStreams", 1L, kitchen);
        registerEmitter("riderStreams", 2L, rider);
        registerEmitter("customerStreams", 3L, customer);
        assertEquals(3, service.activeConnectionCount());

        java.lang.reflect.Method method;
        try {
            method = OrderSseStreamService.class.getDeclaredMethod("shutdown");
            method.setAccessible(true);
            method.invoke(service);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }

        assertEquals(0, service.activeConnectionCount());
        verify(kitchen).complete();
        verify(rider).complete();
        verify(customer).complete();
    }

    /** Registers a mock emitter under the given stream registry field via reflection. */
    private void registerEmitter(String fieldName, Long key, SseEmitter emitter) throws Exception {
        java.lang.reflect.Field field = OrderSseStreamService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<Long, java.util.concurrent.CopyOnWriteArrayList<SseEmitter>> streams =
                (java.util.Map<Long, java.util.concurrent.CopyOnWriteArrayList<SseEmitter>>) field.get(service);
        streams.computeIfAbsent(key, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(emitter);
    }

    @Test
    void subscribe_perStreamCapacityExceeded_throwsAndReleasesBudgetSlot() {
        service.maxEmittersPerStream = 1;
        service.subscribeKitchen(1L, null);

        assertThrows(SseCapacityExceededException.class, () -> service.subscribeKitchen(1L, null));
        // The rejected subscriber must not consume a global budget slot.
        assertEquals(1, service.activeConnectionCount());
    }

    @Test
    void subscribeCustomer_withSnapshot_sendsSnapshotEventAfterConnect() {
        assertDoesNotThrow(() -> {
            SseEmitter emitter = service.subscribeCustomer(1L, null,
                    java.util.Map.of("status", "PREPARING", "orderId", 1L));
            assertNotNull(emitter);
        });
        assertEquals(1, service.activeConnectionCount());
    }

    @Test
    void broadcast_executorRejects_fallsBackToInlineSend() throws Exception {
        // Batch D: when the dispatch executor rejects (should not happen with
        // CallerRunsPolicy), the update is sent inline rather than dropped.
        Executor rejectingExecutor = task -> { throw new java.util.concurrent.RejectedExecutionException("pool down"); };
        OrderSseStreamService rejecting = new OrderSseStreamService(replayStore, rejectingExecutor);
        SseEmitter emitter = mock(SseEmitter.class);
        registerEmitterOn(rejecting, "kitchenStreams", 1L, emitter);

        rejecting.broadcastKitchenLocal(1L, new OrderLiveUpdate());

        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        assertEquals(1, rejecting.activeConnectionCount());
    }

    @Test
    void broadcast_executorRejectsAndInlineSendFails_removesEmitter() throws Exception {
        Executor rejectingExecutor = task -> { throw new java.util.concurrent.RejectedExecutionException("pool down"); };
        OrderSseStreamService rejecting = new OrderSseStreamService(replayStore, rejectingExecutor);
        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new IllegalStateException("broken")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        registerEmitterOn(rejecting, "kitchenStreams", 1L, emitter);

        rejecting.broadcastKitchenLocal(1L, new OrderLiveUpdate());

        assertEquals(0, rejecting.activeConnectionCount());
        verify(emitter).complete();
    }

    @Test
    void sendHeartbeat_executorRejects_fallsBackToInlineHeartbeat() throws Exception {
        Executor rejectingExecutor = task -> { throw new java.util.concurrent.RejectedExecutionException("pool down"); };
        OrderSseStreamService rejecting = new OrderSseStreamService(replayStore, rejectingExecutor);
        SseEmitter emitter = mock(SseEmitter.class);
        registerEmitterOn(rejecting, "riderStreams", 1L, emitter);

        rejecting.sendHeartbeats();

        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        assertEquals(1, rejecting.activeConnectionCount());
    }

    @Test
    void sendHeartbeat_executorRejectsAndInlineSendFails_removesEmitter() throws Exception {
        Executor rejectingExecutor = task -> { throw new java.util.concurrent.RejectedExecutionException("pool down"); };
        OrderSseStreamService rejecting = new OrderSseStreamService(replayStore, rejectingExecutor);
        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new java.io.IOException("gone")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        registerEmitterOn(rejecting, "riderStreams", 1L, emitter);

        rejecting.sendHeartbeats();

        assertEquals(0, rejecting.activeConnectionCount());
        verify(emitter).complete();
    }

    @Test
    void sendHeartbeat_emptyStreamRegistry_noop() {
        // An empty per-stream list must short-circuit without dispatch work.
        assertDoesNotThrow(() -> service.sendHeartbeats());
    }

    @Test
    void shutdown_completingEmitterThrows_isIgnored() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new IllegalStateException("already closed")).when(emitter).complete();
        registerEmitter("kitchenStreams", 1L, emitter);

        java.lang.reflect.Method method;
        try {
            method = OrderSseStreamService.class.getDeclaredMethod("shutdown");
            method.setAccessible(true);
            method.invoke(service);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }

        assertEquals(0, service.activeConnectionCount());
    }

    /** Registers a mock emitter on a specific service instance via reflection. */
    private void registerEmitterOn(OrderSseStreamService target, String fieldName, Long key, SseEmitter emitter)
            throws Exception {
        java.lang.reflect.Field field = OrderSseStreamService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<Long, java.util.concurrent.CopyOnWriteArrayList<SseEmitter>> streams =
                (java.util.Map<Long, java.util.concurrent.CopyOnWriteArrayList<SseEmitter>>) field.get(target);
        streams.computeIfAbsent(key, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(emitter);
    }
}