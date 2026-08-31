package com.bhukkad.restaurant.api;

import com.bhukkad.restaurant.service.RestaurantQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal service-to-service contract (plan §7): order fetches the
 * menu snapshot synchronously during checkout. Never exposed at the edge.
 */
@RestController
@RequestMapping("/internal/menu")
@RequiredArgsConstructor
public class InternalMenuController {

    private final RestaurantQueryService queryService;

    @GetMapping("/snapshot")
    public MenuSnapshot snapshot(@RequestParam Long restaurantId) {
        return queryService.menuSnapshot(restaurantId);
    }
}
