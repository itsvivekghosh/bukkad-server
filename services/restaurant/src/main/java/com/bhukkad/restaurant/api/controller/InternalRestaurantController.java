package com.bhukkad.restaurant.api.controller;

import com.bhukkad.restaurant.domain.entity.Restaurant;
import com.bhukkad.restaurant.domain.repository.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service restaurant ownership oracle ({@code /api/v1/internal/**}).
 *
 * <p>Consumed by order service so its owner-facing order-management surface
 * can verify that a RESTAURANT_OWNER principal actually owns the addressed
 * restaurant (audit HIGH-IDOR-3) — ownership lives in this service's data and
 * is intentionally not replicated. Access is double-enforced: the
 * {@code ServiceJwtAuthFilter} internal-path rule requires a valid mesh token
 * on {@code /api/v1/internal/**}, and method security restricts to
 * ROLE_SERVICE / ROLE_ADMIN. Not exposed at the edge.</p>
 */
@RestController
@RequestMapping("/api/v1/internal/restaurants")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SERVICE') or hasRole('ADMIN')")
public class InternalRestaurantController {

    private final RestaurantRepository restaurantRepository;

    public record OwnerRef(Long ownerId) {
    }

    /**
     * Resolves the owning user of a restaurant. 404 propagates via the usual
     * not-found handling when the restaurant does not exist.
     */
    @GetMapping("/{restaurantId}/owner")
    public OwnerRef owner(@PathVariable Long restaurantId) {
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new com.bhukkad.common.error.ResourceNotFoundException(
                        "Restaurant not found: " + restaurantId));
        return new OwnerRef(restaurant.getOwnerId());
    }
}
