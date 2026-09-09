package com.bhukkad.realtime.sse;

import com.bhukkad.realtime.service.OrderSseStreamService;
import com.bhukkad.realtime.service.SseHeartbeatScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SseHeartbeatSchedulerTest {

    @Mock
    private OrderSseStreamService sseStreamService;

    @Test
    void sendsHeartbeatsOnlyWhenConnectionsAreActive() {
        SseHeartbeatScheduler scheduler = new SseHeartbeatScheduler(sseStreamService);

        when(sseStreamService.activeConnectionCount()).thenReturn(3);
        scheduler.sendHeartbeats();

        verify(sseStreamService).sendHeartbeats();
    }

    @Test
    void skipsHeartbeatsWhenNoConnections() {
        SseHeartbeatScheduler scheduler = new SseHeartbeatScheduler(sseStreamService);

        when(sseStreamService.activeConnectionCount()).thenReturn(0);
        scheduler.sendHeartbeats();

        verify(sseStreamService, never()).sendHeartbeats();
    }

    @Test
    void readsConnectionCountExactlyOncePerTick() {
        SseHeartbeatScheduler scheduler = new SseHeartbeatScheduler(sseStreamService);

        when(sseStreamService.activeConnectionCount()).thenReturn(5);
        scheduler.sendHeartbeats();

        verify(sseStreamService, times(1)).activeConnectionCount();
        verify(sseStreamService, times(1)).sendHeartbeats();
    }
}
