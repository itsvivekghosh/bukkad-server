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
    @Query("SELECT r FROM Review r JOIN FETCH r.customer JOIN FETCH r.restaurant JOIN FETCH r.order WHERE r.customer.id = :customerId ORDER BY r.createdAt DESC")
    List<Review> findByCustomerIdWithDetails(@Param("customerId") Long customerId);

    @Query("SELECT r FROM Review r JOIN FETCH r.customer JOIN FETCH r.restaurant WHERE r.restaurant.id = :restaurantId ORDER BY r.createdAt DESC")
    List<Review> findByRestaurantIdWithDetails(@Param("restaurantId") Long restaurantId);

    @Query("SELECT r FROM Review r JOIN FETCH r.customer JOIN FETCH r.restaurant JOIN FETCH r.order WHERE r.order.id = :orderId")
    Optional<Review> findByOrderIdWithDetails(@Param("orderId") Long orderId);

    List<Review> findByRestaurantIdOrderByCreatedAtDesc(Long restaurantId);

    List<Review> findByCustomerId(Long customerId);

    Optional<Review> findByOrderId(Long orderId);

    @Query("SELECT r FROM Review r JOIN FETCH r.customer JOIN FETCH r.restaurant WHERE r.id = :id")
    Optional<Review> findByIdWithDetails(@Param("id") Long id);

    @Query("SELECT AVG(r.rating) FROM Review r WHERE r.restaurant.id = :restaurantId")
    Double getAverageRatingByRestaurant(@Param("restaurantId") Long restaurantId);

    @Query("SELECT COUNT(r) FROM Review r WHERE r.restaurant.id = :restaurantId")
    Long countByRestaurant(@Param("restaurantId") Long restaurantId);
}