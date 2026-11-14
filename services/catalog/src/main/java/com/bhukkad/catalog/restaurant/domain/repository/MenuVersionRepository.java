package com.bhukkad.catalog.restaurant.domain.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import com.bhukkad.catalog.restaurant.domain.entity.MenuVersion;

public interface MenuVersionRepository extends JpaRepository<MenuVersion, Long> {
    Page<MenuVersion> findByRestaurantIdOrderByVersionDesc(Long restaurantId, Pageable pageable);
    List<MenuVersion> findByRestaurantIdOrderByVersionDesc(Long restaurantId);
    Optional<MenuVersion> findTopByRestaurantIdOrderByVersionDesc(Long restaurantId);
}