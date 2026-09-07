package com.bhukkad.restaurant.api;

import com.bhukkad.restaurant.service.RestaurantQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal service-to-service contract (plan §7): order fetches the
 * menu snapshot synchronously during checkout. The snapshot is the full
 * kitchen menu (prices/currency/availability graph), so the whole surface
 * requires a mesh service token (ServiceJwtAuthFilter path rule + method
 * security here) — it was previously reachable with ANY valid user JWT or,
 * depending on rules, unauthenticated. Not exposed at the edge.
 */
@RestController
@RequestMapping("/internal/menu")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SERVICE') or hasRole('ADMIN')")
public class InternalMenuController {

    private final RestaurantQueryService queryService;

    @GetMapping("/snapshot")
    public MenuSnapshot snapshot(@RequestParam Long restaurantId) {
        return queryService.menuSnapshot(restaurantId);
    }
}
