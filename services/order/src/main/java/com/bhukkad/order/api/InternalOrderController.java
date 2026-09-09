package com.bhukkad.order.api;

import com.bhukkad.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service order ownership oracle ({@code /api/v1/internal/**}).
 *
 * <p>Consumed by realtime (SSE stream authorization) and survey/dispute
 * eligibility checks so ownership can be verified without each caller
 * holding order domain code. Access is double-enforced:
 * {@code ServiceJwtAuthFilter} rejects unauthenticated requests to this path
 * (401 without a valid X-Service-Token once the mesh secret is configured),
 * and method security restricts to ROLE_SERVICE / ROLE_ADMIN.</p>
 */
@RestController
@RequestMapping("/api/v1/internal/orders")
@RequiredArgsConstructor
public class InternalOrderController {

    private final OrderService orderService;

    public record CustomerRef(Long customerId) {
    }

    @GetMapping("/{orderId}/customer")
    @PreAuthorize("hasRole('SERVICE') or hasRole('ADMIN')")
    public CustomerRef customer(@PathVariable Long orderId) {
        // 404 propagates via the service's usual not-found handling.
        OrderResponse order = orderService.getOrder(orderId);
        return new CustomerRef(order.customerId());
    }
}
