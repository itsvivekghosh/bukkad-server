package com.bhukkad.serviceImpl;

import com.bhukkad.dto.request.ReviewRequest;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.DeliveryAgent;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.entity.Review;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.datasource.UseReadReplica;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.CustomerRepository;
import com.bhukkad.repository.DeliveryAgentRepository;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.repository.RestaurantRepository;
import com.bhukkad.repository.ReviewRepository;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.service.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Review lifecycle plus moderation.
 *
 * <h2>Moderation rules enforced here</h2>
 * <ul>
 *   <li>Public restaurant reads return only
 *       {@link Review.ModerationStatus#APPROVED} rows.</li>
 *   <li>Aggregate restaurant rating and review count are computed over approved rows only,
 *       so rejecting a review removes its influence on the score without deleting it.</li>
 *   <li>New reviews inherit the entity default ({@code APPROVED}), matching the column
 *       default in {@code V13__growth_operations.sql}; admins demote to
 *       {@code PENDING}/{@code REJECTED} reactively. Switching to
 *       moderate-before-publish is a one-line change in {@link #createReview}.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReviewServiceImpl implements ReviewService {

    private final ReviewRepository reviewRepository;
    private final OrderRepository orderRepository;
    private final CustomerRepository customerRepository;
    private final RestaurantRepository restaurantRepository;
    private final DeliveryAgentRepository deliveryAgentRepository;
    private final SecurityUtils securityUtils;

    @Override
    @Transactional
    public Review createReview(ReviewRequest request) {
        Long customerId = securityUtils.getCurrentUserId();
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));

        Order order = orderRepository.findByIdWithDetails(request.getOrderId())
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        // Validate order belongs to customer
        if (!order.getCustomer().getId().equals(customerId)) {
            throw new BusinessException("You can only review your own orders");
        }

        // Validate order is delivered
        if (order.getStatus() != Order.OrderStatus.DELIVERED) {
            throw new BusinessException("You can only review delivered orders");
        }

        // Check if review already exists
        if (reviewRepository.findByOrderId(order.getId()).isPresent()) {
            throw new BusinessException("Review already exists for this order");
        }

        Review review = new Review();
        review.setCustomer(customer);
        review.setRestaurant(order.getRestaurant());
        review.setOrder(order);
        review.setRating(request.getRating());
        review.setComment(request.getComment());
        review.setFoodRating(request.getFoodRating());
        review.setDeliveryRating(request.getDeliveryRating());
        review.setImages(request.getImages());

        review = reviewRepository.save(review);

        // Update restaurant rating
        updateRestaurantRating(order.getRestaurant());

        if (request.getDeliveryRating() != null && order.getDeliveryAgent() != null) {
            updateDeliveryAgentRating(order.getDeliveryAgent());
        }

        return review;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Filtered to approved reviews so pending or rejected content never reaches a public
     * restaurant page.
     */
    @Override
    @UseReadReplica
    public List<Review> getRestaurantReviews(Long restaurantId) {
        return reviewRepository.findByRestaurantIdAndModerationStatusWithDetails(
                restaurantId, Review.ModerationStatus.APPROVED);
    }

    @Override
    @UseReadReplica
    public List<Review> getCustomerReviews() {
        Long customerId = securityUtils.getCurrentUserId();
        return reviewRepository.findByCustomerIdWithDetails(customerId);
    }

    @Override
    @UseReadReplica
    public Review getReviewByOrderId(Long orderId) {
        return reviewRepository.findByOrderIdWithDetails(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Review not found"));
    }

    @Override
    @Transactional
    public void deleteReview(Long reviewId) {
        Review review = reviewRepository.findByIdWithDetails(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Review not found"));

        if (!review.getCustomer().getId().equals(securityUtils.getCurrentUserId())) {
            throw new BusinessException("You can only delete your own reviews");
        }

        Restaurant restaurant = review.getRestaurant();
        reviewRepository.delete(review);

        // Update restaurant rating
        updateRestaurantRating(restaurant);
    }

    @Override
    @UseReadReplica
    public List<Review> getModerationQueue(Review.ModerationStatus status) {
        Review.ModerationStatus effective = status != null ? status : Review.ModerationStatus.PENDING;
        return reviewRepository.findByModerationStatusWithDetails(effective);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The rating is recalculated after every transition — including
     * {@code PENDING -> APPROVED} — because the aggregate only counts approved rows.
     */
    @Override
    @Transactional
    public Review moderateReview(Long reviewId, Review.ModerationStatus status) {
        if (status == null) {
            throw new BusinessException("Moderation status is required");
        }

        Review review = reviewRepository.findByIdWithDetails(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Review not found"));

        review.setModerationStatus(status);
        review = reviewRepository.save(review);

        updateRestaurantRating(review.getRestaurant());

        return review;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Authorization follows the codebase-wide owner check: the review's restaurant must be
     * owned by the caller. A blank response clears an existing reply.
     */
    @Override
    @Transactional
    public Review respondToReview(Long reviewId, String response) {
        Review review = reviewRepository.findByIdWithDetails(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Review not found"));

        Restaurant restaurant = review.getRestaurant();
        if (restaurant.getOwner() == null
                || !restaurant.getOwner().getId().equals(securityUtils.getCurrentUserId())) {
            throw new BusinessException("You can only respond to reviews of your own restaurant");
        }

        String trimmed = response != null ? response.trim() : null;
        review.setOwnerResponse(trimmed == null || trimmed.isEmpty() ? null : trimmed);

        return reviewRepository.save(review);
    }

    /**
     * Recomputes the restaurant's aggregate rating and review count over approved reviews only.
     * A restaurant with no approved reviews falls back to {@code 0.0} / {@code 0}.
     */
    private void updateRestaurantRating(Restaurant restaurant) {
        Double averageRating = reviewRepository.getAverageRatingByRestaurantAndStatus(
                restaurant.getId(), Review.ModerationStatus.APPROVED);
        Long totalReviews = reviewRepository.countByRestaurantAndStatus(
                restaurant.getId(), Review.ModerationStatus.APPROVED);

        restaurant.setAverageRating(averageRating != null ? averageRating : 0.0);
        restaurant.setTotalReviews(totalReviews != null ? totalReviews.intValue() : 0);
        restaurantRepository.save(restaurant);
    }

    private void updateDeliveryAgentRating(DeliveryAgent agent) {
        Double averageRating = reviewRepository.getAverageDeliveryRatingByAgent(agent.getId());
        Long totalReviews = reviewRepository.countDeliveryRatingsByAgent(agent.getId());
        agent.setAverageRating(averageRating != null ? averageRating : 0.0);
        deliveryAgentRepository.save(agent);
    }
}