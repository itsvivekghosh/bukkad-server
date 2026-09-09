package com.bhukkad.restaurant.api;

import com.bhukkad.restaurant.service.MenuSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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