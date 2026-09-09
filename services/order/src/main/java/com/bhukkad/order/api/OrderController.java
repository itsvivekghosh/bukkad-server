package com.bhukkad.order.api;

import com.bhukkad.common.security.PrincipalGuard;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.order.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Order service public API ({@code /api/v1/orders}).
 *
 * <p>Reads/cancellations enforce owner-or-admin; creates force the customerId
 * to the JWT subject so an order can never be placed under someone else's
 * account.</p>
 */
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public OrderResponse create(@AuthenticationPrincipal TokenPrincipal principal,
                                @Valid @RequestBody CreateOrderRequest request) {
        // Ignore any body-supplied customerId: identity comes from the token.
        Long customerId = principal == null ? null : principal.userId();
        PrincipalGuard.requireAuthenticated(principal);
        return orderService.createOrder(
                new CreateOrderRequest(customerId, request.restaurantId(), request.items()));
    }

    @GetMapping("/{orderId}")
    public OrderResponse get(@AuthenticationPrincipal TokenPrincipal principal,
                             @PathVariable Long orderId) {
        OrderResponse order = orderService.getOrder(orderId);
        PrincipalGuard.requireSelfOrAdmin(principal, order.customerId());
        return order;
    }

    @PostMapping("/{orderId}/cancel")
    public OrderResponse cancel(@AuthenticationPrincipal TokenPrincipal principal,
                                @PathVariable Long orderId) {
        OrderResponse order = orderService.getOrder(orderId);
        PrincipalGuard.requireSelfOrAdmin(principal, order.customerId());
        orderService.cancelOrder(orderId);
        return orderService.getOrder(orderId);
    }

    /**
     * Internal details view for cross-service consumers (e.g. supportticket
     * dispute auto-resolution). Requires either a valid service JWT via
     * {@code ServiceJwtAuthFilter} (ROLE_SERVICE), an ADMIN, or the order's
     * own customer — the javadoc promise was previously unenforced, letting
     * ANY authenticated user read ANY order's customerId (ownership oracle).
     */
    @GetMapping("/{orderId}/details")
    public OrderDetailsResponse getOrderDetails(@PathVariable Long orderId,
                                                @AuthenticationPrincipal TokenPrincipal principal,
                                                org.springframework.security.core.Authentication authentication) {
        // Privilege check BEFORE any data access: an unauthenticated probe
        // must not even trigger the DB read (existence oracle via timing).
        OrderDetailsResponse details;
        boolean privileged = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_SERVICE".equals(a.getAuthority())
                        || "ROLE_ADMIN".equals(a.getAuthority()));
        if (privileged) {
            details = orderService.getOrderDetails(orderId);
        } else {
            PrincipalGuard.requireAuthenticated(principal);
            details = orderService.getOrderDetails(orderId);
            PrincipalGuard.requireSelfOrAdmin(principal, details.customerId());
        }
        return details;
    }
}
