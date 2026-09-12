package com.bhukkad.restaurant.api.controller;

import com.bhukkad.restaurant.domain.service.impl.MenuSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import com.bhukkad.restaurant.api.dto.response.MenuItemDto;
import com.bhukkad.restaurant.api.dto.response.RestaurantSummary;

/**
 * Full-text search endpoint (port of monolith {@code SearchController}) —
 * backed by the tsvector port in {@link MenuSearchService}.
 */
@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
public class SearchController {

    private final MenuSearchService searchService;

    @GetMapping("/restaurants")
    public List<RestaurantSummary> restaurants(@RequestParam String q) {
        return searchService.searchRestaurants(q);
    }

    @GetMapping("/menu-items")
    public List<MenuItemDto> menuItems(@RequestParam String q) {
        return searchService.searchMenuItems(q);
    }
}