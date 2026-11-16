package com.bhukkad.catalog.restaurant.domain.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import com.bhukkad.catalog.restaurant.domain.entity.MenuCategory;

public interface MenuCategoryRepository extends JpaRepository<MenuCategory, Long> {
    Page<MenuCategory> findByRestaurantIdAndActiveTrue(Long restaurantId, Pageable pageable);
    List<MenuCategory> findByRestaurantIdAndActiveTrue(Long restaurantId);
    Page<MenuCategory> findByRestaurantIdOrderByDisplayOrderAsc(Long restaurantId, Pageable pageable);
    List<MenuCategory> findByRestaurantIdOrderByDisplayOrderAsc(Long restaurantId);

    @Query("SELECT c FROM MenuCategory c WHERE c.id = :id")
    Optional<MenuCategory> findByIdWithRestaurant(@Param("id") Long id);

    @Query("SELECT c FROM MenuCategory c WHERE c.restaurantId = :restaurantId ORDER BY c.displayOrder ASC")
    Page<MenuCategory> findByRestaurantIdWithRestaurantOrderByDisplayOrderAsc(@Param("restaurantId") Long restaurantId, Pageable pageable);
    @Query("SELECT c FROM MenuCategory c WHERE c.restaurantId = :restaurantId ORDER BY c.displayOrder ASC")
    List<MenuCategory> findByRestaurantIdWithRestaurantOrderByDisplayOrderAsc(@Param("restaurantId") Long restaurantId);
}
