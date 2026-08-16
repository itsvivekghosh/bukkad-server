package com.bhukkad.live;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderSseHeartbeatSchedulerTest {

    @Mock
    private OrderSseStreamService sseStreamService;

    @InjectMocks
    private OrderSseHeartbeatScheduler scheduler;

    @Test
    void sendHeartbeats_delegatesToStreamService() {
        scheduler.sendHeartbeats();

        verify(sseStreamService).sendHeartbeats();
    }
}
