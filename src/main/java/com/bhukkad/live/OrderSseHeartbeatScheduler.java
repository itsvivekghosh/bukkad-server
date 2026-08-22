package com.bhukkad.live;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


@Slf4j
@Component
@RequiredArgsConstructor
public class OrderSseHeartbeatScheduler {

    private final OrderSseStreamService sseStreamService;

    @Scheduled(fixedDelayString = "${app.live.sse.heartbeat-interval-ms:25000}")
    public void sendHeartbeats() {
        sseStreamService.sendHeartbeats();
    }
}
