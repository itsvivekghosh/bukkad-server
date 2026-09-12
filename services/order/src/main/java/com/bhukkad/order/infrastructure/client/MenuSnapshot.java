package com.bhukkad.order.infrastructure.client;

import java.util.List;

/**
 * Menu snapshot returned by the Restaurant service.
 */
public class MenuSnapshot {
    private Long restaurantId;
    private String restaurantName;
    private List<MenuItemDto> items;

    public MenuSnapshot() {
    }

    public MenuSnapshot(Long restaurantId, String restaurantName, List<MenuItemDto> items) {
        this.restaurantId = restaurantId;
        this.restaurantName = restaurantName;
        this.items = items;
    }

    public Long getRestaurantId() {
        return restaurantId;
    }

    public String getRestaurantName() {
        return restaurantName;
    }

    public List<MenuItemDto> getItems() {
        return items;
    }

    public void setRestaurantId(Long restaurantId) {
        this.restaurantId = restaurantId;
    }

    public void setRestaurantName(String restaurantName) {
        this.restaurantName = restaurantName;
    }

    public void setItems(List<MenuItemDto> items) {
        this.items = items;
    }
}
