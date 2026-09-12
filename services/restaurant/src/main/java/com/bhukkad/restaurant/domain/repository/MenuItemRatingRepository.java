package com.bhukkad.restaurant.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import com.bhukkad.restaurant.domain.entity.MenuItemRating;

public interface MenuItemRatingRepository extends JpaRepository<MenuItemRating, Long> {
    Optional<MenuItemRating> findByMenuItemIdAndCustomerId(Long menuItemId, Long customerId);
}