package com.bhukkad.repository;

import com.bhukkad.entity.MenuCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MenuCategoryRepository extends JpaRepository<MenuCategory, Long> {
    List<MenuCategory> findByRestaurantIdAndActiveTrue(Long restaurantId);
    List<MenuCategory> findByRestaurantIdOrderByDisplayOrderAsc(Long restaurantId);
}