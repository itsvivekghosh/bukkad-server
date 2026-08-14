package com.bhukkad.repository;

import com.bhukkad.entity.MenuCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MenuCategoryRepository extends JpaRepository<MenuCategory, Long> {
    List<MenuCategory> findByRestaurantIdAndActiveTrue(Long restaurantId);
    List<MenuCategory> findByRestaurantIdOrderByDisplayOrderAsc(Long restaurantId);

    @Query("SELECT c FROM MenuCategory c " +
            "JOIN FETCH c.restaurant r " +
            "LEFT JOIN FETCH r.owner " +
            "WHERE c.id = :id")
    Optional<MenuCategory> findByIdWithRestaurant(@Param("id") Long id);

    @Query("SELECT c FROM MenuCategory c " +
            "JOIN FETCH c.restaurant " +
            "WHERE c.restaurant.id = :restaurantId " +
            "ORDER BY c.displayOrder ASC")
    List<MenuCategory> findByRestaurantIdWithRestaurantOrderByDisplayOrderAsc(@Param("restaurantId") Long restaurantId);
}