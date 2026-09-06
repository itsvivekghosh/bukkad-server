package com.bhukkad.search.dto.response;

import java.util.List;

public class UnifiedSearchResponse {
    private List<RestaurantSearchResult> restaurants;
    private List<MenuItemSearchResult> menuItems;
    private int restaurantCount;
    private int menuItemCount;

    public UnifiedSearchResponse() {}

    public UnifiedSearchResponse(List<RestaurantSearchResult> restaurants, List<MenuItemSearchResult> menuItems,
                                int restaurantCount, int menuItemCount) {
        this.restaurants = restaurants;
        this.menuItems = menuItems;
        this.restaurantCount = restaurantCount;
        this.menuItemCount = menuItemCount;
    }

    public List<RestaurantSearchResult> getRestaurants() { return restaurants; }
    public void setRestaurants(List<RestaurantSearchResult> restaurants) { this.restaurants = restaurants; }
    public List<MenuItemSearchResult> getMenuItems() { return menuItems; }
    public void setMenuItems(List<MenuItemSearchResult> menuItems) { this.menuItems = menuItems; }
    public int getRestaurantCount() { return restaurantCount; }
    public void setRestaurantCount(int restaurantCount) { this.restaurantCount = restaurantCount; }
    public int getMenuItemCount() { return menuItemCount; }
    public void setMenuItemCount(int menuItemCount) { this.menuItemCount = menuItemCount; }
}
