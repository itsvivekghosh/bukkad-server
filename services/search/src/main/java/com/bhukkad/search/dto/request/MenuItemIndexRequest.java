package com.bhukkad.search.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Upsert payload for a menu-item search document, sent by the monolith
 * whenever a menu item is created or updated. Only {@code id} and {@code name}
 * are required for autocomplete; the remaining fields enrich unified search.
 */
public record MenuItemIndexRequest(
        Long id,
        @NotBlank String name,
        String description,
        String categoryName,
        @PositiveOrZero Double price,
        @PositiveOrZero Double originalPrice,
        Double discountPercentage,
        Boolean available,
        String foodType,
        Boolean isVeg,
        String imageUrl,
        Integer preparationTime,
        Boolean bestseller,
        String restaurantName
) {}