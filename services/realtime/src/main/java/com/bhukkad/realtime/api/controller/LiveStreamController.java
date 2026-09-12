package com.bhukkad.realtime.api.controller;

import com.bhukkad.common.security.PrincipalGuard;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.realtime.infrastructure.client.OrderOwnershipClient;
import com.bhukkad.realtime.domain.event.OrderLiveUpdate;
import com.bhukkad.realtime.domain.service.OrderLiveRelay;
import com.bhukkad.realtime.domain.service.OrderSseStreamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@RestController
@RequestMapping("/api/v1/live")
@RequiredArgsConstructor
public class LiveStreamController {

    private final OrderSseStreamService sseStreamService;
    private final OrderOwnershipClient orderOwnershipClient;
    private final OrderLiveRelay orderLiveRelay;

    /**
     * Subscribe to kitchen updates for a restaurant. Kitchen traffic includes
     * order contents and prep state — owner staff only. Restaurant ownership
     * lives in the restaurant service, so realtime admits the
     * RESTAURANT_OWNER/ADMIN/SERVICE scopes (no cross-service owner table here).
     */
    @GetMapping(value = "/kitchen/{restaurantId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAnyRole('RESTAURANT_OWNER', 'ADMIN', 'SERVICE')")
    public SseEmitter subscribeKitchen(
            @PathVariable Long restaurantId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        log.debug("SSE kitchen subscribe | restaurantId={} | lastEventId={}", restaurantId, lastEventId);
        return sseStreamService.subscribeKitchen(restaurantId, lastEventId);
    }

    /**
     * Subscribe to rider updates. Rider streams carry live GPS + assigned
     * orders: agent/ops scopes only. Object-level binding (audit HIGH-IDOR-4):
     * a DELIVERY_AGENT principal may only open ITS OWN stream — the path
     * {@code agentId} must equal the JWT subject. ADMIN/SERVICE (mesh callers
     * such as ops dashboards proxying a view) bypass the self-match.
     */
    @GetMapping(value = "/rider/{agentId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAnyRole('DELIVERY_AGENT', 'ADMIN', 'SERVICE')")
    public SseEmitter subscribeRider(
            @AuthenticationPrincipal TokenPrincipal principal,
            @PathVariable Long agentId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        PrincipalGuard.requireAuthenticated(principal);
        String scope = principal.scope() == null ? "" : principal.scope().toUpperCase(java.util.Locale.ROOT);
        boolean privileged = "ADMIN".equals(scope) || "SERVICE".equals(scope);
        if (!privileged && !"DELIVERY_AGENT".equals(scope)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Rider stream access required");
        }
        if (!privileged && !principal.userId().equals(agentId)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Not this rider's stream");
        }
        log.debug("SSE rider subscribe | agentId={} | lastEventId={}", agentId, lastEventId);
        return sseStreamService.subscribeRider(agentId, lastEventId);
    }

    /**
     * Subscribe to an order's live feed. The order customer binding is owned
     * by the order service, so ownership is verified there via the internal
     * contract (fail-closed: no answer / no match = 403, never a stream).
     * ADMIN and SERVICE (mesh callers such as the rider app proxying a view)
     * are privileged.
     */
    @GetMapping(value = "/order/{orderId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribeCustomer(
            @AuthenticationPrincipal TokenPrincipal principal,
            @PathVariable Long orderId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        PrincipalGuard.requireAuthenticated(principal);
        String scope = principal.scope() == null ? "" : principal.scope().toUpperCase(java.util.Locale.ROOT);
        boolean privileged = "ADMIN".equals(scope) || "SERVICE".equals(scope);
        if (!privileged && !orderOwnershipClient.ownsOrder(principal.userId(), orderId)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Not the order owner");
        }
        log.debug("SSE customer subscribe | orderId={} | lastEventId={}", orderId, lastEventId);
        return sseStreamService.subscribeCustomer(orderId, lastEventId, null);
    }

    /**
     * Inject an order live update to the kitchen stream. Service/ops-only —
     * an open broadcast endpoint let any caller inject arbitrary updates
     * (including forged rider GPS) to every connected client. Published
     * through the relay so EVERY pod's emitters fan out, not just this one's.
     */
    @PostMapping("/kitchen/{restaurantId}/broadcast")
    @PreAuthorize("hasAnyRole('ADMIN', 'SERVICE')")
    public ResponseEntity<Void> broadcastKitchen(
            @PathVariable Long restaurantId,
            @RequestBody OrderLiveUpdate update) {
        update.setRestaurantId(restaurantId);
        orderLiveRelay.publish(update);
        return ResponseEntity.ok().build();
    }

    /**
     * Inject an order live update to a rider stream. Service/ops-only.
     */
    @PostMapping("/rider/{agentId}/broadcast")
    @PreAuthorize("hasAnyRole('ADMIN', 'SERVICE')")
    public ResponseEntity<Void> broadcastRider(
            @PathVariable Long agentId,
            @RequestBody OrderLiveUpdate update) {
        update.setDeliveryAgentId(agentId);
        orderLiveRelay.publish(update);
        return ResponseEntity.ok().build();
    }

    /**
     * Inject an order live update to the customer stream. Service/ops-only.
     */
    @PostMapping("/order/{orderId}/broadcast")
    @PreAuthorize("hasAnyRole('ADMIN', 'SERVICE')")
    public ResponseEntity<Void> broadcastCustomer(
            @PathVariable Long orderId,
            @RequestBody OrderLiveUpdate update) {
        update.setOrderId(orderId);
        orderLiveRelay.publish(update);
        return ResponseEntity.ok().build();
    }

    /**
     * Ops visibility: active SSE connection count. ADMIN/SERVICE only —
     * it counts per-tenant connections.
     */
    @GetMapping("/stats/connections")
    @PreAuthorize("hasAnyRole('ADMIN', 'SERVICE')")
    public ResponseEntity<Integer> getActiveConnections() {
        return ResponseEntity.ok(sseStreamService.activeConnectionCount());
    }
}
