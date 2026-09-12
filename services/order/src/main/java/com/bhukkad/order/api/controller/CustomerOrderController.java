package com.bhukkad.order.api.controller;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.order.domain.service.impl.OrderService;
import java.util.List;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.bhukkad.order.api.dto.request.CreateOrderRequest;
import com.bhukkad.order.api.dto.response.OrderResponse;

/**
 * Customer order surface: place an order and list order history. The caller
 * identity comes from the validated JWT; the path customerId must match the
 * principal (admins may act across customers). The service layer validates
 * items/pricing; empty carts fail with 400 via BusinessException.
 */
@RestController
@RequestMapping("/api/v1/customers/{customerId}/orders")
public class CustomerOrderController {

    private static final String SCOPE_ADMIN = "ADMIN";

    private final OrderService orderService;

    public CustomerOrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public OrderResponse create(@AuthenticationPrincipal TokenPrincipal principal,
                                @PathVariable Long customerId,
                                @RequestBody CreateOrderRequest request) {
        requireSelfOrAdmin(principal, customerId);
        if (request == null || request.restaurantId() == null) {
            throw new BusinessException("restaurantId is required");
        }
        if (request.customerId() != null && !request.customerId().equals(customerId)) {
            throw new BusinessException("customerId mismatch between path and body");
        }
        // Path identity wins over any body-supplied customerId (IDOR guard).
        return orderService.createOrder(
                new CreateOrderRequest(customerId, request.restaurantId(), request.items()));
    }

    @GetMapping
    public List<OrderResponse> myOrders(@AuthenticationPrincipal TokenPrincipal principal,
                                        @PathVariable Long customerId) {
        requireSelfOrAdmin(principal, customerId);
        return this.orderService.getOrdersForCustomer(customerId);
    }

    private static void requireSelfOrAdmin(TokenPrincipal principal, Long customerId) {
        if (principal == null) {
            // Filter disabled in this profile: treat as unauthenticated caller.
            throw new AccessDeniedException("Authentication required");
        }
        boolean admin = SCOPE_ADMIN.equalsIgnoreCase(String.valueOf(principal.scope()));
        if (!admin && !customerId.equals(principal.userId())) {
            throw new AccessDeniedException("Cannot act on another customer's orders");
        }
    }
}
