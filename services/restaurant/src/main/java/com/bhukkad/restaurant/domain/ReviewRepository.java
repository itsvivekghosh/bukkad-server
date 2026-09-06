package com.bhukkad.restaurant.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    List<Review> findByRestaurantId(Long restaurantId);

    List<Review> findByStatus(String status);

    List<Review> findByRestaurantIdOrderByCreatedAtDesc(Long restaurantId);

    List<Review> findByCustomerId(Long customerId);

    @Query("SELECT AVG(r.rating) FROM Review r WHERE r.restaurantId = :restaurantId")
    Double getAverageRatingByRestaurant(@Param("restaurantId") Long restaurantId);

    @Query("SELECT COUNT(r) FROM Review r WHERE r.restaurantId = :restaurantId")
    Long countByRestaurant(@Param("restaurantId") Long restaurantId);

    @Query("SELECT AVG(r.rating) FROM Review r WHERE r.restaurantId = :restaurantId AND r.status = :status")
    Double getAverageRatingByRestaurantAndStatus(@Param("restaurantId") Long restaurantId,
                                                 @Param("status") String status);

    @Query("SELECT COUNT(r) FROM Review r WHERE r.restaurantId = :restaurantId AND r.status = :status")
    Long countByRestaurantAndStatus(@Param("restaurantId") Long restaurantId,
                                    @Param("status") String status);

    @Query("SELECT AVG(r.deliveryRating) FROM Review r WHERE r.deliveryRating IS NOT NULL")
    Double getAverageDeliveryRating();

    @Query("SELECT COUNT(r) FROM Review r WHERE r.deliveryRating IS NOT NULL")
    Long countDeliveryRatings();
}
