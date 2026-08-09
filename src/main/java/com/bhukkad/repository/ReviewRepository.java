package com.bhukkad.repository;

import com.bhukkad.entity.Review;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReviewRepository extends JpaRepository<Review, Long> {
    List<Review> findByRestaurantIdOrderByCreatedAtDesc(Long restaurantId);

    List<Review> findByCustomerId(Long customerId);

    Optional<Review> findByOrderId(Long orderId);

    @Query("SELECT AVG(r.rating) FROM Review r WHERE r.restaurant.id = :restaurantId")
    Double getAverageRatingByRestaurant(@Param("restaurantId") Long restaurantId);

    @Query("SELECT COUNT(r) FROM Review r WHERE r.restaurant.id = :restaurantId")
    Long countByRestaurant(@Param("restaurantId") Long restaurantId);
}