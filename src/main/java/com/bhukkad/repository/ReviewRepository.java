package com.bhukkad.repository;

import com.bhukkad.entity.Review;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Data access for {@link Review}.
 *
 * <p>Every read that returns entities uses {@code JOIN FETCH} because
 * {@code spring.jpa.open-in-view} is {@code false}: associations must be initialised
 * inside the service transaction or serialisation fails with a lazy-init error.
 *
 * <p>The moderation-aware queries below are shaped to the composite indexes added in
 * {@code V17__trust_and_compliance.sql} section 4 —
 * {@code idx_review_restaurant_moderation (restaurant_id, moderation_status)} for public
 * reads and rating aggregation, and
 * {@code idx_review_moderation_queue (moderation_status, created_at)} for the admin queue.
 * Moderation status is always bound as a parameter rather than written as a JPQL literal,
 * which keeps the nested {@link Review.ModerationStatus} enum out of the query string.
 */
@Repository
public interface ReviewRepository extends JpaRepository<Review, Long> {
    @Query("SELECT r FROM Review r JOIN FETCH r.customer JOIN FETCH r.restaurant JOIN FETCH r.order WHERE r.customer.id = :customerId ORDER BY r.createdAt DESC")
    List<Review> findByCustomerIdWithDetails(@Param("customerId") Long customerId);

    @Query("SELECT r FROM Review r JOIN FETCH r.customer JOIN FETCH r.restaurant JOIN FETCH r.order WHERE r.restaurant.id = :restaurantId ORDER BY r.createdAt DESC")
    List<Review> findByRestaurantIdWithDetails(@Param("restaurantId") Long restaurantId);

    @Query("SELECT r FROM Review r JOIN FETCH r.customer JOIN FETCH r.restaurant JOIN FETCH r.order WHERE r.order.id = :orderId")
    Optional<Review> findByOrderIdWithDetails(@Param("orderId") Long orderId);

    List<Review> findByRestaurantIdOrderByCreatedAtDesc(Long restaurantId);

    List<Review> findByCustomerId(Long customerId);

    Optional<Review> findByOrderId(Long orderId);

    @Query("SELECT r FROM Review r JOIN FETCH r.customer JOIN FETCH r.restaurant JOIN FETCH r.order WHERE r.id = :id")
    Optional<Review> findByIdWithDetails(@Param("id") Long id);

    @Query("SELECT AVG(r.rating) FROM Review r WHERE r.restaurant.id = :restaurantId")
    Double getAverageRatingByRestaurant(@Param("restaurantId") Long restaurantId);

    @Query("SELECT COUNT(r) FROM Review r WHERE r.restaurant.id = :restaurantId")
    Long countByRestaurant(@Param("restaurantId") Long restaurantId);

    @Query("SELECT AVG(r.deliveryRating) FROM Review r WHERE r.order.deliveryAgent.id = :agentId AND r.deliveryRating IS NOT NULL")
    Double getAverageDeliveryRatingByAgent(@Param("agentId") Long agentId);

    @Query("SELECT COUNT(r) FROM Review r WHERE r.order.deliveryAgent.id = :agentId AND r.deliveryRating IS NOT NULL")
    Long countDeliveryRatingsByAgent(@Param("agentId") Long agentId);

    // ------------------------------------------------------------------
    // Moderation-aware reads
    // ------------------------------------------------------------------

    /**
     * Public restaurant review feed, restricted to a single moderation status.
     * Uses {@code idx_review_restaurant_moderation}.
     */
    @Query("SELECT r FROM Review r JOIN FETCH r.customer JOIN FETCH r.restaurant JOIN FETCH r.order "
            + "WHERE r.restaurant.id = :restaurantId AND r.moderationStatus = :status ORDER BY r.createdAt DESC")
    List<Review> findByRestaurantIdAndModerationStatusWithDetails(@Param("restaurantId") Long restaurantId,
                                                                 @Param("status") Review.ModerationStatus status);

    /**
     * Admin moderation queue: oldest first, so the review that has been waiting longest is
     * handled first. Uses {@code idx_review_moderation_queue}.
     */
    @Query("SELECT r FROM Review r JOIN FETCH r.customer JOIN FETCH r.restaurant JOIN FETCH r.order "
            + "WHERE r.moderationStatus = :status ORDER BY r.createdAt ASC")
    List<Review> findByModerationStatusWithDetails(@Param("status") Review.ModerationStatus status);

    /**
     * Average rating over reviews in the given moderation status. Returns {@code null} when
     * the restaurant has no matching reviews.
     */
    @Query("SELECT AVG(r.rating) FROM Review r WHERE r.restaurant.id = :restaurantId AND r.moderationStatus = :status")
    Double getAverageRatingByRestaurantAndStatus(@Param("restaurantId") Long restaurantId,
                                                 @Param("status") Review.ModerationStatus status);

    /** Review count for a restaurant restricted to the given moderation status. */
    @Query("SELECT COUNT(r) FROM Review r WHERE r.restaurant.id = :restaurantId AND r.moderationStatus = :status")
    Long countByRestaurantAndStatus(@Param("restaurantId") Long restaurantId,
                                    @Param("status") Review.ModerationStatus status);
}