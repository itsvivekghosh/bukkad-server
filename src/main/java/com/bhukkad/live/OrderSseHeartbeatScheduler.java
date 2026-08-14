package com.bhukkad.live;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

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
