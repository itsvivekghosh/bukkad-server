package com.bhukkad.restaurant.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.restaurant.domain.Review;
import com.bhukkad.restaurant.domain.ReviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Review submission + moderation (Batch C depth).
 */
@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ReviewRepository reviewRepository;

    @Transactional
    public Review submit(Long restaurantId, Long customerId, int rating, String comment) {
        if (rating < 1 || rating > 5) {
            throw new BusinessException("Rating must be between 1 and 5");
        }
        Review review = new Review();
        review.setRestaurantId(restaurantId);
        review.setCustomerId(customerId);
        review.setRating(rating);
        review.setComment(comment);
        review.setStatus(Review.STATUS_PENDING);
        return reviewRepository.save(review);
    }

    @Transactional
    public Review moderate(Long reviewId, String status) {
        if (!List.of(Review.STATUS_APPROVED, Review.STATUS_REJECTED).contains(status)) {
            throw new BusinessException("Invalid moderation status: " + status);
        }
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new com.bhukkad.common.error.ResourceNotFoundException("Review not found: " + reviewId));
        review.setStatus(status);
        return reviewRepository.save(review);
    }

    /**
     * Removes a review (customer self-service delete; ADMIN may remove any).
     * Unknown id → 404; foreign author → 403 (IDOR guard, audit B-class).
     */
    @Transactional
    public void deleteFor(com.bhukkad.common.security.TokenPrincipal principal, Long reviewId) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new com.bhukkad.common.error.ResourceNotFoundException("Review not found: " + reviewId));
        com.bhukkad.common.security.PrincipalGuard.requireSelfOrAdmin(principal, review.getCustomerId());
        reviewRepository.delete(review);
    }

    @Transactional(readOnly = true)
    public List<Review> byRestaurant(Long restaurantId) {
        return reviewRepository.findByRestaurantId(restaurantId);
    }

    /**
     * Public review listing: only APPROVED reviews leave the service.
     */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<Review> approvedByRestaurant(
            Long restaurantId, org.springframework.data.domain.Pageable pageable) {
        return reviewRepository.findByRestaurantIdAndStatusOrderByCreatedAtDesc(
                restaurantId, Review.STATUS_APPROVED, pageable);
    }

    /** The customer's own reviews across every restaurant (any status). */
    public org.springframework.data.domain.Page<Review> byCustomer(
            Long customerId, org.springframework.data.domain.Pageable pageable) {
        return reviewRepository.findByCustomerIdOrderByCreatedAtDesc(customerId, pageable);
    }
}
