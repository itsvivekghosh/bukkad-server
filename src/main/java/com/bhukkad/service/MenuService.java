package com.bhukkad.service;

import com.bhukkad.dto.request.MenuImageUploadRequest;
import com.bhukkad.dto.request.MenuCategoryRequest;
import com.bhukkad.dto.request.MenuItemRequest;
import com.bhukkad.dto.response.MenuCategoryResponse;
import com.bhukkad.dto.response.MenuImageUploadResponse;
import com.bhukkad.dto.response.MenuItemResponse;

import java.util.List;

public interface MenuService {

    // Category
    MenuCategoryResponse createCategory(Long restaurantId, MenuCategoryRequest request);
    List<MenuCategoryResponse> getCategoriesByRestaurant(Long restaurantId);
    MenuCategoryResponse updateCategory(Long categoryId, MenuCategoryRequest request);
    void deleteCategory(Long categoryId);

    // Menu Items
    MenuItemResponse createMenuItem(MenuItemRequest request);
    MenuItemResponse getMenuItemById(Long id);
    List<MenuItemResponse> getMenuItemsByCategory(Long categoryId);
    List<MenuItemResponse> getMenuItemsByRestaurant(Long restaurantId);
    MenuItemResponse updateMenuItem(Long id, MenuItemRequest request);
    void deleteMenuItem(Long id);
    void toggleItemAvailability(Long itemId, Boolean available);
    List<MenuItemResponse> getBestsellers(Long restaurantId);
    List<MenuItemResponse> getRecommended(Long restaurantId);
    List<MenuItemResponse> searchMenuItems(String keyword);
    List<MenuItemResponse> getLowStockItems(Long restaurantId, Integer threshold);

    MenuImageUploadResponse createMenuItemImageUploadUrl(Long menuItemId, MenuImageUploadRequest request);
}