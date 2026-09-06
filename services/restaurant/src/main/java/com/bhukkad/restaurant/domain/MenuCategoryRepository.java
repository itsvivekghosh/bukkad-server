package com.bhukkad.restaurant.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MenuCategoryRepository extends JpaRepository<MenuCategory, Long> {
    List<MenuCategory> findByRestaurantIdAndActiveTrue(Long restaurantId);
    List<MenuCategory> findByRestaurantIdOrderByDisplayOrderAsc(Long restaurantId);

    @Query("SELECT c FROM MenuCategory c WHERE c.id = :id")
    Optional<MenuCategory> findByIdWithRestaurant(@Param("id") Long id);

    @Query("SELECT c FROM MenuCategory c WHERE c.restaurantId = :restaurantId ORDER BY c.displayOrder ASC")
    List<MenuCategory> findByRestaurantIdWithRestaurantOrderByDisplayOrderAsc(@Param("restaurantId") Long restaurantId);
}
