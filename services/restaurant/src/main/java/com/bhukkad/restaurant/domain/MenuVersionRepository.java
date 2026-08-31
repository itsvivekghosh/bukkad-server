package com.bhukkad.restaurant.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MenuVersionRepository extends JpaRepository<MenuVersion, Long> {
    List<MenuVersion> findByRestaurantIdOrderByVersionDesc(Long restaurantId);
}