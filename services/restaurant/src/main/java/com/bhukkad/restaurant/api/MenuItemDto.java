package com.bhukkad.restaurant.api;

import java.math.BigDecimal;

/**
 * Public menu item DTO (read model).
 */
public record MenuItemDto(Long id, String name, String description, BigDecimal price, boolean available) {
}
