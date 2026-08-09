package com.bhukkad.repository;

import com.bhukkad.entity.MenuItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MenuItemRepository extends JpaRepository<MenuItem, Long> {
    List<MenuItem> findByCategoryIdAndAvailableTrue(Long categoryId);

    List<MenuItem> findByCategoryRestaurantIdAndAvailableTrue(Long restaurantId);

    @Query("SELECT m FROM MenuItem m WHERE m.category.restaurant.id = :restaurantId AND " +
            "m.available = true AND m.bestseller = true")
    List<MenuItem> findBestsellersByRestaurant(@Param("restaurantId") Long restaurantId);

    @Query("SELECT m FROM MenuItem m WHERE m.category.restaurant.id = :restaurantId AND " +
            "m.available = true AND m.isVeg = :isVeg")
    List<MenuItem> findByRestaurantAndFoodType(@Param("restaurantId") Long restaurantId,
                                               @Param("isVeg") Boolean isVeg);

    @Query("SELECT m FROM MenuItem m WHERE m.available = true AND " +
            "LOWER(m.name) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    List<MenuItem> searchByName(@Param("keyword") String keyword);
}