package com.bhukkad.service;

import com.bhukkad.dto.request.MenuItemRequest;
import com.bhukkad.dto.response.MenuItemResponse;
import com.bhukkad.entity.MenuCategory;

import java.util.List;

public interface MenuService {
    // Category operations
    MenuCategory createCategory(Long restaurantId, MenuCategory category);
    List<MenuCategory> getCategoriesByRestaurant(Long restaurantId);
    MenuCategory updateCategory(Long categoryId, MenuCategory category);
    void deleteCategory(Long categoryId);

    // Menu item operations
    MenuItemResponse createMenuItem(MenuItemRequest request);
    MenuItemResponse getMenuItemById(Long id);
    List<MenuItemResponse> getMenuItemsByCategory(Long categoryId);
    List<MenuItemResponse> getMenuItemsByRestaurant(Long restaurantId);
    MenuItemResponse updateMenuItem(Long id, MenuItemRequest request);
    void deleteMenuItem(Long id);

    // Item availability
    void toggleItemAvailability(Long itemId, Boolean available);

    // Special items
    List<MenuItemResponse> getBestsellers(Long restaurantId);
    List<MenuItemResponse> getRecommended(Long restaurantId);

    // Search
    List<MenuItemResponse> searchMenuItems(String keyword);
}