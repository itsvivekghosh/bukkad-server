package com.bhukkad.realtime.api;

import com.bhukkad.realtime.dto.OrderLiveUpdate;
import com.bhukkad.realtime.service.OrderSseStreamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@RestController
@RequestMapping("/api/v1/live")
@RequiredArgsConstructor
public class LiveStreamController {

    private final OrderSseStreamService sseStreamService;

    /**
     * Subscribe to kitchen updates for a restaurant.
     */
    @GetMapping(value = "/kitchen/{restaurantId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribeKitchen(
            @PathVariable Long restaurantId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        log.debug("SSE kitchen subscribe | restaurantId={} | lastEventId={}", restaurantId, lastEventId);
        return sseStreamService.subscribeKitchen(restaurantId, lastEventId);
    }

    /**
     * Subscribe to rider updates.
     */
    @GetMapping(value = "/rider/{agentId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribeRider(
            @PathVariable Long agentId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        log.debug("SSE rider subscribe | agentId={} | lastEventId={}", agentId, lastEventId);
        return sseStreamService.subscribeRider(agentId, lastEventId);
    }

    /**
     * Subscribe to order updates for a customer.
     */
    @GetMapping(value = "/order/{orderId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribeCustomer(
            @PathVariable Long orderId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        log.debug("SSE customer subscribe | orderId={} | lastEventId={}", orderId, lastEventId);
        return sseStreamService.subscribeCustomer(orderId, lastEventId, null);
    }

    /**
     * Broadcast an order live update to kitchen stream.
     */
    @PostMapping("/kitchen/{restaurantId}/broadcast")
    public ResponseEntity<Void> broadcastKitchen(
            @PathVariable Long restaurantId,
            @RequestBody OrderLiveUpdate update) {
        sseStreamService.broadcastKitchen(restaurantId, update);
        return ResponseEntity.ok().build();
    }

    /**
     * Broadcast an order live update to rider stream.
     */
    @PostMapping("/rider/{agentId}/broadcast")
    public ResponseEntity<Void> broadcastRider(
            @PathVariable Long agentId,
            @RequestBody OrderLiveUpdate update) {
        sseStreamService.broadcastRider(agentId, update);
        return ResponseEntity.ok().build();
    }

    /**
     * Broadcast an order live update to customer stream.
     */
    @PostMapping("/order/{orderId}/broadcast")
    public ResponseEntity<Void> broadcastCustomer(
            @PathVariable Long orderId,
            @RequestBody OrderLiveUpdate update) {
        sseStreamService.broadcastCustomer(orderId, update);
        return ResponseEntity.ok().build();
    }

    /**
     * Get active SSE connection count.
     */
    @GetMapping("/stats/connections")
    public ResponseEntity<Integer> getActiveConnections() {
        return ResponseEntity.ok(sseStreamService.activeConnectionCount());
    }
}
