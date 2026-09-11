package com.bhukkad.delivery.api;

import com.bhukkad.delivery.service.DeliveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service delivery lifecycle surface: order-service (mesh) drives
 * rider assignment and delivery completion. {@code authenticated()} at the
 * security chain only proves SOME identity, so both mutations are gated to
 * ROLE_SERVICE (mesh token, same contract as order's InternalOrderController)
 * plus ROLE_ADMIN for ops override. A user JWT — customer, rider or owner —
 * must never drive them directly; rider self-service lives on
 * {@link RiderSelfController}, which binds to the JWT subject.
 */
@RestController
@RequestMapping("/api/v1/deliveries")
@RequiredArgsConstructor
public class DeliveryController {

    private final DeliveryService deliveryService;

    @PostMapping("/orders/{orderId}/assign")
    @PreAuthorize("hasRole('SERVICE') or hasRole('ADMIN')")
    public Object assign(@PathVariable Long orderId) {
        return deliveryService.assign(orderId);
    }

    @PostMapping("/orders/{orderId}/delivered")
    @PreAuthorize("hasRole('SERVICE') or hasRole('ADMIN')")
    public Object markDelivered(@PathVariable Long orderId) {
        return deliveryService.markDelivered(orderId);
    }
}
