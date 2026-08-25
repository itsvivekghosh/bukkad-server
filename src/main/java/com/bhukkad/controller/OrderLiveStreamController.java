package com.bhukkad.controller;

import com.bhukkad.config.ApiPaths;

import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.OrderResponse;
import com.bhukkad.entity.User;
import com.bhukkad.live.OrderLiveAccessService;
import com.bhukkad.live.OrderSseStreamService;
import com.bhukkad.live.RiderLocationTrackingService;
import com.bhukkad.ratelimit.RateLimited;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
@RequestMapping(ApiPaths.V1_PREFIX + "/orders/stream")
@RequiredArgsConstructor
public class OrderLiveStreamController {

    private static final String LAST_EVENT_ID_HEADER = "Last-Event-ID";

    private final OrderSseStreamService sseStreamService;
    private final OrderLiveAccessService orderLiveAccessService;
    private final SecurityUtils securityUtils;
    private final OrderService orderService;
    private final RiderLocationTrackingService riderLocationTrackingService;

    @GetMapping(value = "/kitchen/{restaurantId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    public SseEmitter streamKitchen(
            @PathVariable Long restaurantId,
            @RequestHeader(value = LAST_EVENT_ID_HEADER, required = false) String lastEventId) {
        User user = securityUtils.getCurrentUser();
        orderLiveAccessService.verifyKitchenAccess(user, restaurantId);
        return sseStreamService.subscribeKitchen(restaurantId, lastEventId);
    }

    @GetMapping(value = "/rider", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasRole('DELIVERY_AGENT')")
    public SseEmitter streamRider(
            @RequestHeader(value = LAST_EVENT_ID_HEADER, required = false) String lastEventId) {
        return sseStreamService.subscribeRider(securityUtils.getCurrentUserId(), lastEventId);
    }

    @GetMapping(value = "/customer/{orderId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasRole('CUSTOMER')")
    @RateLimited("order-track")
    public SseEmitter streamCustomerOrder(
            @PathVariable Long orderId,
            @RequestHeader(value = LAST_EVENT_ID_HEADER, required = false) String lastEventId) {
        User user = securityUtils.getCurrentUser();
        orderLiveAccessService.verifyCustomerAccess(user, orderId);
        OrderResponse snapshot = orderService.getOrderById(orderId);
        return sseStreamService.subscribeCustomer(orderId, lastEventId, snapshot);
    }

    /**
     * Issues an anonymous tracking token for an order so a guest (no account)
     * can follow the delivery via the token-protected SSE stream.
     */
    @PostMapping("/customer/{orderId}/tracking-token")
    @PreAuthorize("hasRole('CUSTOMER')")
    @RateLimited("order-track")
    public ResponseEntity<ApiResponse<Map<String, String>>> createTrackingToken(
            @PathVariable Long orderId) {
        User user = securityUtils.getCurrentUser();
        orderLiveAccessService.verifyCustomerAccess(user, orderId);
        String token = riderLocationTrackingService.createTrackingToken(orderId);
        return ResponseEntity.ok(ApiResponse.success(Map.of("trackingToken", token)));
    }

    /**
     * Anonymous SSE stream for guest tracking. Access is granted only when a
     * valid tracking token for the order is supplied; the security chain allows
     * this path through without a CUSTOMER role.
     */
    @GetMapping(value = "/customer-token/{orderId}", produces = {MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.APPLICATION_JSON_VALUE})
    @RateLimited("order-track")
    public SseEmitter streamCustomerOrderByToken(
            @PathVariable Long orderId,
            @RequestParam String token,
            @RequestHeader(value = LAST_EVENT_ID_HEADER, required = false) String lastEventId) {
        if (!riderLocationTrackingService.isValidTrackingToken(orderId, token)) {
            throw new com.bhukkad.exception.UnauthorizedException("Invalid or expired tracking token");
        }
        OrderResponse snapshot = orderService.getOrderById(orderId);
        return sseStreamService.subscribeCustomer(orderId, lastEventId, snapshot);
    }
}
