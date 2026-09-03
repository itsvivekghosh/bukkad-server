package com.bhukkad.restaurant.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MenuVersionRepository extends JpaRepository<MenuVersion, Long> {
    List<MenuVersion> findByRestaurantIdOrderByVersionDesc(Long restaurantId);
    Optional<MenuVersion> findTopByRestaurantIdOrderByVersionDesc(Long restaurantId);
}