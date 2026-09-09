package com.bhukkad.restaurant.service;

import com.bhukkad.common.cache.RedisCacheService;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.restaurant.api.MenuItemDto;
import com.bhukkad.restaurant.api.MenuSnapshot;
import com.bhukkad.restaurant.api.RestaurantSummary;
import com.bhukkad.restaurant.domain.MenuItem;
import com.bhukkad.restaurant.domain.MenuItemRepository;
import com.bhukkad.restaurant.domain.Restaurant;
import com.bhukkad.restaurant.domain.RestaurantRepository;
import com.bhukkad.restaurant.service.cache.RestaurantCacheKeys;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Read-side service for restaurant browse and menu snapshot. Serves the public
 * browse API and the internal {@code /internal/menu/snapshot} contract that
 * order calls during checkout (plan §7).
 *
 * <p>PERF-3: the snapshot is server-cached ({@code menu:restaurant:<id>},
 * 300 s) through {@link RedisCacheService}; the checkout chord therefore reads
 * L1/L2 instead of the database on warm requests. Mutations evict through
 * {@code MenuCacheInvalidator}; when no Redis beans are present the endpoint
 * behaves exactly like before (DB per request).</p>
 */
@Service
@RequiredArgsConstructor
public class RestaurantQueryService {

    private final RestaurantRepository restaurantRepository;
    private final MenuItemRepository menuItemRepository;
    private final ObjectProvider<RedisCacheService> cacheProvider;
    private final ObjectMapper objectMapper;

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
        RedisCacheService cache = cacheProvider.getIfAvailable();
        if (cache == null) {
            return loadMenuSnapshot(restaurantId);
        }
        // Cache the canonical JSON so every served response (and the order
        // client parsing it) is byte-identical to the live DTO serialization.
        String json = cache.getOrCompute(RestaurantCacheKeys.menuSnapshot(restaurantId),
                String.class, RestaurantCacheKeys.MENU_SNAPSHOT_TTL_SECONDS,
                () -> writeSnapshot(loadMenuSnapshot(restaurantId)));
        return readSnapshot(json);
    }

    private MenuSnapshot loadMenuSnapshot(Long restaurantId) {
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found: " + restaurantId));
        List<MenuItemDto> items = menuItemRepository
                .findByRestaurantIdAndIsAvailableTrue(restaurantId)
                .stream().map(this::toDto).toList();
        return new MenuSnapshot(restaurant.getId(), restaurant.getName(), items);
    }

    private String writeSnapshot(MenuSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Menu snapshot serialization failed", e);
        }
    }

    private MenuSnapshot readSnapshot(String json) {
        try {
            return objectMapper.readValue(json, MenuSnapshot.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Menu snapshot deserialization failed", e);
        }
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
