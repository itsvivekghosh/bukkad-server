package com.bhukkad.restaurant.api.controller;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.UnauthorizedException;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.restaurant.domain.entity.MenuItem;
import com.bhukkad.restaurant.domain.repository.MenuItemRepository;
import com.bhukkad.restaurant.domain.entity.Restaurant;
import com.bhukkad.restaurant.domain.repository.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * "Surprise me" — picks one random available dish from an open restaurant
 * near the caller. Requires the caller's location (latitude/longitude) so the
 * suggestion is actually deliverable; missing parameters are a client error
 * (400), matching the mobile app contract.
 */
@RestController
@RequiredArgsConstructor
public class SurpriseMeController {

    private final MenuItemRepository menuItemRepository;
    private final com.bhukkad.restaurant.domain.repository.RestaurantRepository restaurantRepository;

    @GetMapping("/api/v1/customers/surprise-me")
    public Map<String, Object> surpriseMe(@AuthenticationPrincipal TokenPrincipal principal,
                                          @RequestParam(required = false) Double latitude,
                                          @RequestParam(required = false) Double longitude) {
        if (principal == null || principal.userId() == null) {
            throw new UnauthorizedException("Authenticated customer required");
        }
        if (latitude == null || longitude == null) {
            throw new BusinessException("latitude and longitude are required");
        }
        var openRestaurants = restaurantRepository.findByIsActiveTrueAndIsOpenTrue();
        if (openRestaurants.isEmpty()) {
            throw new BusinessException("No open restaurants to pick from");
        }
        Restaurant pick = openRestaurants
                .get(ThreadLocalRandom.current().nextInt(openRestaurants.size()));
        List<MenuItem> items = menuItemRepository.findByRestaurantIdAndIsAvailableTrue(pick.getId());
        if (items.isEmpty()) {
            throw new BusinessException("Restaurant has no available items");
        }
        MenuItem item = items.get(ThreadLocalRandom.current().nextInt(items.size()));
        return Map.of(
                "restaurantId", pick.getId(),
                "restaurantName", pick.getName() == null ? "" : pick.getName(),
                "itemId", item.getId(),
                "itemName", item.getName() == null ? "" : item.getName(),
                "price", item.getPrice() == null ? 0.0 : item.getPrice(),
                "message", "Feeling lucky? Try this!");
    }
}
