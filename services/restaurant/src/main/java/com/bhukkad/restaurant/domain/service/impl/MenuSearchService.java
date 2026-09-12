package com.bhukkad.restaurant.domain.service.impl;

import com.bhukkad.restaurant.api.dto.response.MenuItemDto;
import com.bhukkad.restaurant.api.dto.response.RestaurantSummary;
import com.bhukkad.restaurant.domain.entity.MenuItem;
import com.bhukkad.restaurant.domain.repository.MenuItemRepository;
import com.bhukkad.restaurant.domain.entity.Restaurant;
import com.bhukkad.restaurant.domain.repository.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Full-text search over restaurants and menu items (Batch C depth). PG port of
 * the monolith's MySQL FULLTEXT queries — now tsvector + GIN + ts_rank.
 */
@Service
@RequiredArgsConstructor
public class MenuSearchService {

    private static final int DEFAULT_LIMIT = 20;

    private final RestaurantRepository restaurantRepository;
    private final MenuItemRepository menuItemRepository;

    @Transactional(readOnly = true)
    public List<RestaurantSummary> searchRestaurants(String keyword) {
        return restaurantRepository.fullTextSearchByName(keyword.trim(), DEFAULT_LIMIT)
                .stream().map(this::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public List<MenuItemDto> searchMenuItems(String keyword) {
        return menuItemRepository.fullTextSearch(keyword.trim(), DEFAULT_LIMIT)
                .stream().map(this::toDto).toList();
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
