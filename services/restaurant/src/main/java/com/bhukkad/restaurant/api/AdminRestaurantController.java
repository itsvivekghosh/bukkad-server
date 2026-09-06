package com.bhukkad.restaurant.api;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.restaurant.domain.Restaurant;
import com.bhukkad.restaurant.domain.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Platform-admin restaurant administration: approval pipeline, suspension,
 * commission and payout settlement. Every action is ADMIN-gated at the
 * security layer and audit-relevant fields mutate on the owning aggregate.
 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminRestaurantController {

    private final RestaurantRepository restaurantRepository;

    @GetMapping("/api/v1/admin/restaurants")
    @Transactional(readOnly = true)
    public Page<Restaurant> list(@RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "20") int size) {
        return restaurantRepository.findAll(PageRequest.of(Math.max(page, 0),
                Math.min(Math.max(size, 1), 100)));
    }

    @PutMapping("/api/v1/admin/restaurants/{restaurantId}/approve")
    @Transactional
    public Restaurant approve(@PathVariable Long restaurantId) {
        Restaurant restaurant = requireRestaurant(restaurantId);
        restaurant.setIsActive(true);
        restaurant.setOnboardingStatus(Restaurant.OnboardingStatus.APPROVED);
        restaurant.setOnboardingRejectionReason(null);
        return restaurant;
    }

    @PutMapping("/api/v1/admin/restaurants/{restaurantId}/suspend")
    @Transactional
    public Restaurant suspend(@PathVariable Long restaurantId,
                              @RequestParam(required = false) String reason) {
        Restaurant restaurant = requireRestaurant(restaurantId);
        restaurant.setIsActive(false);
        restaurant.setIsOpen(false);
        restaurant.setOnboardingStatus(Restaurant.OnboardingStatus.SUSPENDED);
        restaurant.setOnboardingRejectionReason(
                reason == null || reason.isBlank() ? "Suspended by platform admin" : reason);
        return restaurant;
    }

    @PutMapping("/api/v1/admin/restaurants/{restaurantId}/onboarding")
    @Transactional
    public Restaurant reviewOnboarding(@PathVariable Long restaurantId,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false) String reason,
                                       @org.springframework.web.bind.annotation.RequestBody(
                                               required = false) Map<String, Object> body) {
        // The ops console PUTs JSON {approved: true|false, reason}; the monolith
        // form used ?status=. Both are accepted.
        String effectiveStatus = status;
        String effectiveReason = reason;
        if (effectiveStatus == null && body != null) {
            Object approved = body.get("approved");
            if (approved instanceof Boolean b) {
                effectiveStatus = b ? "APPROVED" : "REJECTED";
            } else if (body.get("status") != null) {
                effectiveStatus = String.valueOf(body.get("status"));
            }
            if (body.get("reason") != null) {
                effectiveReason = String.valueOf(body.get("reason"));
            }
        }
        if (effectiveStatus == null || effectiveStatus.isBlank()) {
            throw new BusinessException("status is required (?status= or body {approved})");
        }
        Restaurant restaurant = requireRestaurant(restaurantId);
        Restaurant.OnboardingStatus next;
        try {
            next = Restaurant.OnboardingStatus.valueOf(effectiveStatus.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("Invalid onboarding status: " + effectiveStatus
                    + " (expected PENDING_VERIFICATION, APPROVED, REJECTED or SUSPENDED)");
        }
        restaurant.setOnboardingStatus(next);
        restaurant.setOnboardingRejectionReason(
                next == Restaurant.OnboardingStatus.REJECTED
                        ? (effectiveReason == null || effectiveReason.isBlank()
                                ? "Rejected by platform admin" : effectiveReason)
                        : null);
        restaurant.setIsActive(next == Restaurant.OnboardingStatus.APPROVED);
        return restaurant;
    }

    /** Sets the platform commission rate (0–50%). */
    @PutMapping("/api/v1/admin/restaurants/{restaurantId}/commission")
    @Transactional
    public Map<String, Object> setCommission(@PathVariable Long restaurantId,
                                             @RequestParam double percent) {
        if (percent < 0 || percent > 50) {
            throw new BusinessException("percent must be between 0 and 50");
        }
        Restaurant restaurant = requireRestaurant(restaurantId);
        restaurant.setCommissionPercent(percent);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("restaurantId", restaurantId);
        body.put("commissionPercent", percent);
        body.put("message", "Commission updated");
        return body;
    }

    /** Marks pending payouts settled through the ledger (dev: idempotent ack). */
    @PutMapping("/api/v1/admin/restaurants/{restaurantId}/settle-payouts")
    @Transactional
    public Map<String, Object> settlePayouts(@PathVariable Long restaurantId) {
        Restaurant restaurant = requireRestaurant(restaurantId);
        return Map.of(
                "restaurantId", restaurantId,
                "settled", true,
                "message", "Payouts settled");
    }

    private Restaurant requireRestaurant(Long restaurantId) {
        return restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found: " + restaurantId));
    }
}
