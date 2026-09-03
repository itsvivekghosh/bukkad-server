package com.bhukkad.restaurant.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Restaurant-service port of the monolith
 * {@code com.bhukkad.repository.MenuCategoryRepository} (relation-fetch queries
 * dropped; the service-local entity uses plain IDs).
 */
@Repository
public interface MenuCategoryRepository extends JpaRepository<MenuCategory, Long> {

    List<MenuCategory> findByRestaurantIdAndActiveTrue(Long restaurantId);

    List<MenuCategory> findByRestaurantIdOrderByDisplayOrderAsc(Long restaurantId);
}
