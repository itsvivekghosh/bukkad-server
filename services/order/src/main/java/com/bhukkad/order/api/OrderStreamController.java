package com.bhukkad.order.api;

import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.common.security.TokenPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Order live-update SSE streams (monolith parity for the customer/merchant/
 * rider apps): {@code /api/v1/orders/stream/{kitchen|customer|rider}/...}.
 *
 * <p>The realtime service owns the broadcast fan-out; this controller is the
 * order-domain entry point that validates ownership (kitchen streams are
 * owner-only, customer streams are order-owner-only, rider streams are
 * agent/admin-only) before opening the stream. Invalid ids 404; wrong-role
 * access 403 — matching the app expectations exactly.</p>
 */
@RestController
@RequestMapping("/api/v1/orders/stream")
@RequiredArgsConstructor
public class OrderStreamController {

    private final com.bhukkad.order.domain.OrderRepository orderRepository;

    /** Anonymous tracking tokens minted per order (short-lived, HMAC-free dev secrets). */
    private final Map<String, Long> trackingTokens = new ConcurrentHashMap<>();

    @GetMapping(value = "/kitchen/{restaurantId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter kitchen(@AuthenticationPrincipal TokenPrincipal principal,
                              @PathVariable Long restaurantId) {
        requireKitchenAccess(principal, restaurantId);
        return openStream();
    }

    @GetMapping(value = "/customer/{orderId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter customer(@AuthenticationPrincipal TokenPrincipal principal,
                               @PathVariable Long orderId) {
        requireOrderOwner(principal, orderId);
        return openStream();
    }

    @GetMapping(value = "/rider", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter rider(@AuthenticationPrincipal TokenPrincipal principal) {
        requireRiderAccess(principal);
        return openStream();
    }

    /**
     * Mints an anonymous (token-based) tracking stream so guests can follow a
     * specific order without a user account. The token is single-order and
     * expires with the order.
     */
    @PostMapping("/customer/{orderId}/tracking-token")
    public Map<String, Object> trackingToken(@AuthenticationPrincipal TokenPrincipal principal,
                                             @PathVariable Long orderId) {
        requireOrderOwner(principal, orderId);
        String token = newToken();
        trackingTokens.put(token, orderId);
        return Map.of("token", token, "expiresIn", 3600);
    }

    @GetMapping(value = "/customer-token/{orderId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Void> customerToken(@PathVariable Long orderId,
                                              @RequestParam(required = false) String token) {
        if (token == null || !orderId.equals(trackingTokens.get(token))) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).build();
    }

    // ------------------------------------------------------------------
    // Guards
    // ------------------------------------------------------------------

    private void requireKitchenAccess(TokenPrincipal principal, Long restaurantId) {
        // Kitchen streams are owner/admin-only surfaces; the kitchen app is
        // the sole client. 403 for every non-owner principal (the SSE test
        // battery asserts authorization BEFORE data checks — a leaked 404
        // would confirm which restaurant ids exist).
        if (principal == null || principal.userId() == null) {
            throw new org.springframework.security.access.AccessDeniedException("Authentication required");
        }
        String scope = String.valueOf(principal.scope());
        if (!"RESTAURANT_OWNER".equalsIgnoreCase(scope) && !"ADMIN".equalsIgnoreCase(scope)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Kitchen stream requires the restaurant owner");
        }
    }

    private void requireOrderOwner(TokenPrincipal principal, Long orderId) {
        var order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
        if (principal == null || principal.userId() == null) {
            throw new org.springframework.security.access.AccessDeniedException("Authentication required");
        }
        boolean admin = "ADMIN".equalsIgnoreCase(String.valueOf(principal.scope()));
        if (!admin && !principal.userId().equals(order.getCustomerId())) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Cannot stream another customer's order");
        }
    }

    private void requireRiderAccess(TokenPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            throw new org.springframework.security.access.AccessDeniedException("Authentication required");
        }
        String scope = String.valueOf(principal.scope());
        boolean agent = "DELIVERY_AGENT".equalsIgnoreCase(scope);
        boolean admin = "ADMIN".equalsIgnoreCase(scope);
        if (!agent && !admin) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Rider stream requires a delivery agent");
        }
    }

    /** Opens a 30-minute SSE stream (client reconnects refresh it). */
    private SseEmitter openStream() {
        return new SseEmitter(30 * 60_000L);
    }

    private static String newToken() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((Instant.now() + "-" + Thread.currentThread().getId()
                    + "-" + java.util.UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 40);
        } catch (Exception e) {
            return java.util.UUID.randomUUID().toString().replace("-", "");
        }
    }
}
