package com.bhukkad.restaurant.api;

/**
 * Public restaurant summary DTO (read model).
 */
public record RestaurantSummary(Long id, String name, String description,
                                Long cuisineId, String address, String phone,
                                boolean active, double avgRating) {
}
