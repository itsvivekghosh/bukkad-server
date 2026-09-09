package com.bhukkad.realtime.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SseHeartbeatScheduler {

    private final OrderSseStreamService sseStreamService;

    @Scheduled(fixedDelayString = "${app.live.heartbeat-interval-ms:30000}")
    public void sendHeartbeats() {
        int count = sseStreamService.activeConnectionCount();
        if (count > 0) {
            log.debug("Sending heartbeat to {} SSE connections", count);
            sseStreamService.sendHeartbeats();
        }
    }
}
