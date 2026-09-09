package com.bhukkad.restaurant.service;

import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.restaurant.api.MenuItemDto;
import com.bhukkad.restaurant.api.MenuSnapshot;
import com.bhukkad.restaurant.api.RestaurantSummary;
import com.bhukkad.restaurant.domain.MenuItem;
import com.bhukkad.restaurant.domain.MenuItemRepository;
import com.bhukkad.restaurant.domain.Restaurant;
import com.bhukkad.restaurant.domain.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Read-side service for restaurant browse and menu snapshot. Serves the public
 * browse API and the internal {@code /internal/menu/snapshot} contract that
 * order calls during checkout (plan §7).
 */
@Service
@RequiredArgsConstructor
public class RestaurantQueryService {

    private final RestaurantRepository restaurantRepository;
    private final MenuItemRepository menuItemRepository;

    @Transactional(readOnly = true)
    public List<RestaurantSummary> browseByCuisine(Long cuisineId) {
        return restaurantRepository.findByCuisineIdAndIsActiveTrue(cuisineId)
                .stream().map(this::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public List<RestaurantSummary> search(String name) {
        return restaurantRepository.findByNameContainingIgnoreCaseAndIsActiveTrue(name)
                .stream().map(this::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public MenuSnapshot menuSnapshot(Long restaurantId) {
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found: " + restaurantId));
        List<MenuItemDto> items = menuItemRepository
                .findByRestaurantIdAndIsAvailableTrue(restaurantId)
                .stream().map(this::toDto).toList();
        return new MenuSnapshot(restaurant.getId(), restaurant.getName(), items);
    }

    private RestaurantSummary toSummary(Restaurant r) {
        return new RestaurantSummary(r.getId(), r.getName(), r.getDescription(), r.getCuisineId(),
                r.getAddress(), r.getPhone(), Boolean.TRUE.equals(r.getIsActive()), r.getAvgRating());
    }

    private MenuItemDto toDto(MenuItem m) {
        return new MenuItemDto(m.getId(), m.getName(), m.getDescription(), m.getPrice(),
                Boolean.TRUE.equals(m.getIsAvailable()));
    }
}
