package com.bhukkad.restaurant.api;

import java.util.List;

/**
 * Menu snapshot used by the internal order contract.
 */
public record MenuSnapshot(Long restaurantId, String restaurantName, List<MenuItemDto> items) {
}
