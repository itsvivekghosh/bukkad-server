package com.bhukkad.order.api;

import com.bhukkad.common.security.PrincipalGuard;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.order.domain.Subscription;
import com.bhukkad.order.service.SocialOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Subscription endpoints (port of monolith's {@code SubscriptionController}).
 * Subscriptions are per-customer assets: identity comes from the JWT, never
 * from a request parameter.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class SubscriptionController {

    private final SocialOrderService socialService;

    @PostMapping("/subscriptions")
    public Subscription subscribe(@AuthenticationPrincipal TokenPrincipal principal,
                                  @RequestParam Long restaurantId,
                                  @RequestParam String plan) {
        PrincipalGuard.requireAuthenticated(principal);
        if (plan == null || plan.isBlank() || plan.length() > 50) {
            throw new com.bhukkad.common.error.BusinessException("Invalid plan");
        }
        return socialService.subscribe(principal.userId(), restaurantId, plan);
    }

    @GetMapping("/customers/{customerId}/subscriptions")
    public List<Subscription> subscriptions(@AuthenticationPrincipal TokenPrincipal principal,
                                            @PathVariable Long customerId) {
        PrincipalGuard.requireSelfOrAdmin(principal, customerId);
        return socialService.subscriptions(customerId);
    }
}
