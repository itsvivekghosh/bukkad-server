package com.bhukkad.serviceImpl;

import com.bhukkad.datasource.UseReadReplica;
import com.bhukkad.dto.response.MenuItemResponse;
import com.bhukkad.dto.response.RestaurantResponse;
import com.bhukkad.dto.response.UnifiedSearchResponse;
import com.bhukkad.metrics.BusinessMetrics;
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
