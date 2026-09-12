package com.bhukkad.identity.domain.repository;

import com.bhukkad.identity.domain.entity.FavoriteRestaurant;

import com.bhukkad.identity.domain.entity.FavoriteRestaurant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FavoriteRestaurantRepository extends JpaRepository<FavoriteRestaurant, Long> {
    List<FavoriteRestaurant> findByCustomerId(Long customerId);
    void deleteByCustomerIdAndRestaurantId(Long customerId, Long restaurantId);
}