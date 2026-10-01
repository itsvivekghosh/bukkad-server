package com.bhukkad.catalog.restaurant.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import com.bhukkad.catalog.restaurant.domain.entity.MenuItemRating;

public interface MenuItemRatingRepository extends JpaRepository<MenuItemRating, Long> {
    Optional<MenuItemRating> findByMenuItemIdAndCustomerId(Long menuItemId, Long customerId);
}