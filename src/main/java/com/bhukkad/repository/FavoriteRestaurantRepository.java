package com.bhukkad.repository;

import com.bhukkad.entity.FavoriteRestaurant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FavoriteRestaurantRepository extends JpaRepository<FavoriteRestaurant, Long> {
    List<FavoriteRestaurant> findByCustomerIdOrderByCreatedAtDesc(Long customerId);

    Optional<FavoriteRestaurant> findByCustomerIdAndRestaurantId(Long customerId, Long restaurantId);

    boolean existsByCustomerIdAndRestaurantId(Long customerId, Long restaurantId);

    void deleteByCustomerIdAndRestaurantId(Long customerId, Long restaurantId);
}
