package com.bhukkad.order.api.controller;

import com.bhukkad.common.security.PrincipalGuard;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.order.domain.service.impl.OrderService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.bhukkad.order.api.dto.request.CreateOrderRequest;
import com.bhukkad.order.api.dto.response.OrderDetailsResponse;
import com.bhukkad.order.api.dto.response.OrderResponse;
import com.bhukkad.order.domain.entity.Order;

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
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    private static final String FUNNEL_ORDER_CREATE = "order_create";

    private Counter orderCreateCounter() {
        if (meterRegistryProvider == null) {
            return new io.micrometer.core.instrument.noop.NoopCounter(
                    new io.micrometer.core.instrument.Meter.Id(
                            "order.funnel.total",
                            io.micrometer.core.instrument.Tags.of("stage", FUNNEL_ORDER_CREATE),
                            "orders", null, io.micrometer.core.instrument.Meter.Type.COUNTER));
        }
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry == null) {
            return new io.micrometer.core.instrument.noop.NoopCounter(
                    new io.micrometer.core.instrument.Meter.Id(
                            "order.funnel.total",
                            io.micrometer.core.instrument.Tags.of("stage", FUNNEL_ORDER_CREATE),
                            "orders", null, io.micrometer.core.instrument.Meter.Type.COUNTER));
        }
        return registry.counter("order.funnel.total", "stage", FUNNEL_ORDER_CREATE);
    }

    @Operation(summary = "Create a new order", description = "Creates a new order for the authenticated customer")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Order created successfully",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = OrderResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request data",
                    content = @Content),
            @ApiResponse(responseCode = "401", description = "Unauthorized",
                    content = @Content),
            @ApiResponse(responseCode = "500", description = "Internal server error",
                    content = @Content)
    })
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping
    public OrderResponse create(@AuthenticationPrincipal TokenPrincipal principal,
                                @Valid @RequestBody CreateOrderRequest request) {
        // Ignore any body-supplied customerId: identity comes from the token.
        Long customerId = principal == null ? null : principal.userId();
        PrincipalGuard.requireAuthenticated(principal);
        orderCreateCounter().increment();
        return orderService.createOrder(
                new CreateOrderRequest(customerId, request.restaurantId(), request.items()));
    }

    @Operation(summary = "Get order by ID", description = "Retrieves an order by its ID")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Order retrieved successfully",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = OrderResponse.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized",
                    content = @Content),
            @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions",
                    content = @Content),
            @ApiResponse(responseCode = "404", description = "Order not found",
                    content = @Content),
            @ApiResponse(responseCode = "500", description = "Internal server error",
                    content = @Content)
    })
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/{orderId}")
    public OrderResponse get(@AuthenticationPrincipal TokenPrincipal principal,
                             @Parameter(description = "ID of the order to retrieve", required = true)
                             @PathVariable Long orderId) {
        OrderResponse order = orderService.getOrder(orderId);
        PrincipalGuard.requireSelfOrAdmin(principal, order.customerId());
        return order;
    }

    @Operation(summary = "Cancel an order", description = "Cancels an existing order by its ID")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Order cancelled successfully",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = OrderResponse.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized",
                    content = @Content),
            @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions",
                    content = @Content),
            @ApiResponse(responseCode = "404", description = "Order not found",
                    content = @Content),
            @ApiResponse(responseCode = "500", description = "Internal server error",
                    content = @Content)
    })
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{orderId}/cancel")
    public OrderResponse cancel(@AuthenticationPrincipal TokenPrincipal principal,
                                @Parameter(description = "ID of the order to cancel", required = true)
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
    @Operation(summary = "Get order details", description = "Retrieves detailed information about an order")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Order details retrieved successfully",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = OrderDetailsResponse.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized",
                    content = @Content),
            @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions",
                    content = @Content),
            @ApiResponse(responseCode = "404", description = "Order not found",
                    content = @Content),
            @ApiResponse(responseCode = "500", description = "Internal server error",
                    content = @Content)
    })
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/{orderId}/details")
    public OrderDetailsResponse getOrderDetails(@Parameter(description = "ID of the order to retrieve details for", required = true)
                                                @PathVariable Long orderId,
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
