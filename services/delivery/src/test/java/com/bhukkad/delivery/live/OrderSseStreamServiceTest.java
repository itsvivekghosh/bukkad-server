package com.bhukkad.delivery.live;

import com.bhukkad.common.error.SseCapacityExceededException;
import com.bhukkad.delivery.dto.response.OrderLiveUpdate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * SSE registry: registration + connected frame, missed-event replay on
 * subscribe, per-stream and global capacity budgets, executor fallback,
 * eviction of dead emitters, heartbeats and shutdown.
 */
@ExtendWith(MockitoExtension.class)
class OrderSseStreamServiceTest {

    @Mock private OrderLiveReplayStore replayStore;

    private OrderSseStreamService service;

    @BeforeEach
    void setUp() {
        service = new OrderSseStreamService(replayStore, Runnable::run);
    }

    @Test
    void subscribeKitchen_registersEmitterAndCountsIt() {
        SseEmitter emitter = service.subscribeKitchen(9L, null);

        assertThat(emitter).isNotNull();
        assertThat(service.activeConnectionCount()).isEqualTo(1);
        verifyNoInteractions(replayStore);
    }

    @Test
    void subscribeKitchen_replaysMissedEventsForLastEventId() {
        OrderLiveUpdate missed = OrderLiveUpdate.builder().eventId(8L).orderId(7L).build();
        when(replayStore.replayAfter("kitchen:9", 5L)).thenReturn(List.of(missed));

        service.subscribeKitchen(9L, "5");

        verify(replayStore).replayAfter(eq("kitchen:9"), eq(5L));
        assertThat(service.activeConnectionCount()).isEqualTo(1);
    }

    @Test
    void subscribe_unparsableLastEventIdSkipsReplay() {
        service.subscribeRider(3L, "not-a-number");

        verifyNoInteractions(replayStore);
        assertThat(service.activeConnectionCount()).isEqualTo(1);
    }

    @Test
    void subscribeCustomer_sendsSnapshotAfterConnected() {
        Object snapshot = OrderLiveUpdate.builder().orderId(7L).build();

        SseEmitter emitter = service.subscribeCustomer(7L, null, snapshot);

        assertThat(emitter).isNotNull();
        assertThat(service.activeConnectionCount()).isEqualTo(1);
        verifyNoInteractions(replayStore);
    }

    @Test
    void subscribe_secondEmitterOnSameStreamSharesGroup() {
        service.subscribeKitchen(9L, null);
        service.subscribeKitchen(9L, null);

        assertThat(service.activeConnectionCount()).isEqualTo(2);
    }

    @Test
    void subscribe_beyondPerStreamCapacityIsRejected() {
        service.maxEmittersPerStream = 1;
        service.subscribeKitchen(9L, null);

        assertThatThrownBy(() -> service.subscribeKitchen(9L, null))
                .isInstanceOf(SseCapacityExceededException.class)
                .hasMessageContaining("Stream capacity reached");

        assertThat(service.activeConnectionCount()).isEqualTo(1);
    }

    @Test
    void subscribe_beyondGlobalBudgetIsRejected() {
        service.maxTotalEmitters = 2;
        service.subscribeKitchen(9L, null);
        service.subscribeRider(3L, null);

        assertThatThrownBy(() -> service.subscribeCustomer(7L, null, null))
                .isInstanceOf(SseCapacityExceededException.class)
                .hasMessageContaining("budget");

        assertThat(service.activeConnectionCount()).isEqualTo(2);
    }

    @Test
    void broadcastKitchen_withSubscriberSendsWithoutEviction() {
        service.subscribeKitchen(9L, null);
        OrderLiveUpdate update = OrderLiveUpdate.builder().eventId(2L).build();

        service.broadcastKitchen(9L, update);

        assertThat(service.activeConnectionCount()).isEqualTo(1);
    }

    @Test
    void broadcast_forUnknownStreamIsNoOp() {
        service.broadcastKitchen(99L, OrderLiveUpdate.builder().build());
        service.broadcastRider(99L, OrderLiveUpdate.builder().eventId(1L).build());
        service.broadcastCustomer(99L, OrderLiveUpdate.builder().build());

        assertThat(service.activeConnectionCount()).isZero();
    }

    @Test
    void broadcast_deadEmitterIsEvictedFromStreams() {
        SseEmitter emitter = service.subscribeKitchen(9L, null);
        emitter.complete();

        service.broadcastKitchen(9L, OrderLiveUpdate.builder().eventId(1L).build());

        assertThat(service.activeConnectionCount()).isZero();
    }

    @Test
    void broadcast_executorRejectionFallsBackToInlineSend() {
        OrderSseStreamService rejecting =
                new OrderSseStreamService(replayStore, task -> {
                    throw new RejectedExecutionException("saturated");
                });
        rejecting.subscribeKitchen(9L, null);

        rejecting.broadcastKitchen(9L, OrderLiveUpdate.builder().eventId(1L).build());

        assertThat(rejecting.activeConnectionCount()).isEqualTo(1);
    }

    @Test
    void broadcast_rejectedExecutorAndDeadEmitterEvicts() {
        OrderSseStreamService rejecting =
                new OrderSseStreamService(replayStore, task -> {
                    throw new RejectedExecutionException("saturated");
                });
        SseEmitter emitter = rejecting.subscribeKitchen(9L, null);
        emitter.complete();

        rejecting.broadcastKitchen(9L, OrderLiveUpdate.builder().build());

        assertThat(rejecting.activeConnectionCount()).isZero();
    }

    @Test
    void sendHeartbeats_reachesLiveEmitters() {
        service.subscribeKitchen(9L, null);
        service.subscribeRider(3L, null);
        service.subscribeCustomer(7L, null, null);

        service.sendHeartbeats();

        assertThat(service.activeConnectionCount()).isEqualTo(3);
    }

    @Test
    void sendHeartbeats_evictsDeadEmitter() {
        SseEmitter emitter = service.subscribeKitchen(9L, null);
        emitter.complete();

        service.sendHeartbeats();

        assertThat(service.activeConnectionCount()).isZero();
    }

    @Test
    void sendHeartbeats_executorRejectionFallsBackInline() {
        OrderSseStreamService rejecting =
                new OrderSseStreamService(replayStore, task -> {
                    throw new RejectedExecutionException("saturated");
                });
        rejecting.subscribeKitchen(9L, null);

        rejecting.sendHeartbeats();

        assertThat(rejecting.activeConnectionCount()).isEqualTo(1);
    }

    @Test
    void shutdown_completesAndClearsAllStreams() {
        service.subscribeKitchen(9L, null);
        service.subscribeRider(3L, null);

        service.shutdown();

        assertThat(service.activeConnectionCount()).isZero();
    }

    @Test
    void broadcastLocalVariants_routedToMatchingStreams() {
        service.subscribeKitchen(9L, null);
        service.subscribeRider(3L, null);
        service.subscribeCustomer(7L, null, null);
        OrderLiveUpdate update = OrderLiveUpdate.builder().eventId(4L).build();

        service.broadcastKitchenLocal(9L, update);
        service.broadcastRiderLocal(3L, update);
        service.broadcastCustomerLocal(7L, update);

        assertThat(service.activeConnectionCount()).isEqualTo(3);
    }

    @Test
    void subscribeKitchen_replayReturnsNothingLeavesStreamAlive() {
        when(replayStore.replayAfter(eq("kitchen:9"), anyLong())).thenReturn(List.of());

        service.subscribeKitchen(9L, "0");

        assertThat(service.activeConnectionCount()).isEqualTo(1);
    }
}
