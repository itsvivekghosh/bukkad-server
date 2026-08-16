package com.bhukkad.service;

import com.bhukkad.dto.request.ReviewRequest;
import com.bhukkad.entity.Review;

import java.util.List;

/**
 * Customer reviews plus the trust-and-safety operations layered on top of them.
 *
 * <p>Reads split into three audiences:
 * <ul>
 *   <li><b>Public</b> — {@link #getRestaurantReviews(Long)} returns only
 *       {@link Review.ModerationStatus#APPROVED} rows.</li>
 *   <li><b>Admin</b> — {@link #getModerationQueue(Review.ModerationStatus)} and
 *       {@link #moderateReview(Long, Review.ModerationStatus)} drive the queue.</li>
 *   <li><b>Restaurant owner</b> — {@link #respondToReview(Long, String)} attaches a public
 *       reply to a review of the owner's own restaurant.</li>
 * </ul>
 */
public interface ReviewService {
    Review createReview(ReviewRequest request);

    /**
     * Publicly visible reviews for a restaurant, newest first. Pending and rejected reviews
     * are excluded.
     */
    List<Review> getRestaurantReviews(Long restaurantId);

    List<Review> getCustomerReviews();

    Review getReviewByOrderId(Long orderId);

    void deleteReview(Long reviewId);

    /**
     * Admin moderation queue for the given status, oldest first so the longest-waiting review
     * is handled first.
     *
     * @param status status to list; defaults to {@link Review.ModerationStatus#PENDING} when {@code null}
     */
    List<Review> getModerationQueue(Review.ModerationStatus status);

    /**
     * Sets a review's moderation status (admin action) and recalculates the restaurant's
     * aggregate rating, since only approved reviews count towards it.
     *
     * @throws com.bhukkad.exception.ResourceNotFoundException if the review does not exist
     */
    Review moderateReview(Long reviewId, Review.ModerationStatus status);

    /**
     * Attaches or replaces the restaurant owner's public reply to a review.
     *
     * @throws com.bhukkad.exception.BusinessException if the caller does not own the
     *         restaurant the review belongs to
     */
    Review respondToReview(Long reviewId, String response);
}