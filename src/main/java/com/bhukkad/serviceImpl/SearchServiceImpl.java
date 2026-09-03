package com.bhukkad.serviceImpl;

import com.bhukkad.common.datasource.UseReadReplica;
import com.bhukkad.dto.response.MenuItemResponse;
import com.bhukkad.dto.response.RestaurantResponse;
import com.bhukkad.dto.response.UnifiedSearchResponse;
import com.bhukkad.common.metrics.BusinessMetrics;
import com.bhukkad.service.MenuService;
import com.bhukkad.service.RestaurantService;
import com.bhukkad.service.SearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SearchServiceImpl implements SearchService {

    private final RestaurantService restaurantService;
    private final MenuService menuService;
    private final BusinessMetrics businessMetrics;

    @Override
    @UseReadReplica
    public UnifiedSearchResponse unifiedSearch(String keyword) {
        businessMetrics.search();
        if (keyword == null || keyword.trim().length() < 2) {
            return UnifiedSearchResponse.builder()
                    .restaurants(List.of())
                    .menuItems(List.of())
                    .restaurantCount(0)
                    .menuItemCount(0)
                    .build();
        }
        // Sequential is fine for 60s cached; parallel would add thread contention at 1k QPS.
        // Keep serial but both paths are now cached via getListOrCompute with jitter.
        List<RestaurantResponse> restaurants = restaurantService.searchRestaurants(keyword);
        List<MenuItemResponse> menuItems = menuService.searchMenuItems(keyword);
        return UnifiedSearchResponse.builder()
                .restaurants(restaurants)
                .menuItems(menuItems)
                .restaurantCount(restaurants.size())
                .menuItemCount(menuItems.size())
                .build();
    }
}
