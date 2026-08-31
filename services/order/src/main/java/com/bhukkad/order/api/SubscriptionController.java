package com.bhukkad.order.api;

import com.bhukkad.order.domain.Subscription;
import com.bhukkad.order.service.SocialOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Subscription endpoints (port of monolith's {@code SubscriptionController}).
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class SubscriptionController {

    private final SocialOrderService socialService;

    @PostMapping("/subscriptions")
    public Subscription subscribe(@RequestParam Long customerId, @RequestParam Long restaurantId,
                                  @RequestParam String plan) {
        return socialService.subscribe(customerId, restaurantId, plan);
    }

    @GetMapping("/customers/{customerId}/subscriptions")
    public List<Subscription> subscriptions(@PathVariable Long customerId) {
        return socialService.subscriptions(customerId);
    }
}