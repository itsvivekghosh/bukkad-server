package com.bhukkad.restaurant.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MenuItemRatingRepository extends JpaRepository<MenuItemRating, Long> {
    Optional<MenuItemRating> findByMenuItemIdAndCustomerId(Long menuItemId, Long customerId);
}