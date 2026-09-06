package com.bhukkad.restaurant.api;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.common.security.PrincipalGuard;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.restaurant.domain.DynamicPricingRule;
import com.bhukkad.restaurant.service.DynamicPricingService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

/**
 * Dynamic (surge) pricing rules. Mutations are owner-or-admin and the
 * multiplier is bounded — an unbounded 0.01–1000× range previously let any
 * user manipulate menu prices platform-wide.
 */
@RestController
@RequestMapping("/api/v1/pricing")
@RequiredArgsConstructor
public class DynamicPricingController {

    private static final BigDecimal MIN_MULTIPLIER = new BigDecimal("1.0");
    private static final BigDecimal MAX_MULTIPLIER = new BigDecimal("3.0");

    private final DynamicPricingService pricingService;
    private final RestaurantOwnerController ownerGuard;

    @PostMapping
    public DynamicPricingRule create(@AuthenticationPrincipal TokenPrincipal principal,
                                     @RequestParam Long restaurantId, @RequestParam String name,
                                     @RequestParam BigDecimal multiplier,
                                     @RequestParam(required = false) LocalTime startTime,
                                     @RequestParam(required = false) LocalTime endTime) {
        ownerGuard.requireOwnerOrAdmin(principal, restaurantId);
        if (multiplier.compareTo(MIN_MULTIPLIER) < 0 || multiplier.compareTo(MAX_MULTIPLIER) > 0) {
            throw new BusinessException("multiplier must be between 1.0 and 3.0");
        }
        return pricingService.create(restaurantId, name, multiplier, startTime, endTime);
    }

    @GetMapping
    public List<DynamicPricingRule> active(@RequestParam Long restaurantId) {
        return pricingService.active(restaurantId);
    }

    @DeleteMapping("/{ruleId}")
    public void deactivate(@AuthenticationPrincipal TokenPrincipal principal,
                           @PathVariable Long ruleId) {
        // Ownership resolved through the rule's restaurant.
        com.bhukkad.restaurant.domain.DynamicPricingRule rule = pricingService.getRule(ruleId)
                .orElseThrow(() -> new ResourceNotFoundException("Pricing rule not found"));
        ownerGuard.requireOwnerOrAdmin(principal, rule.getRestaurantId());
        pricingService.deactivate(ruleId);
    }
}
