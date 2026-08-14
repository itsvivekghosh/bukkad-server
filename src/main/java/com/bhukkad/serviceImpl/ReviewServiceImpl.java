package com.bhukkad.serviceImpl;

import com.bhukkad.dto.request.ReviewRequest;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.entity.Review;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.CustomerRepository;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.repository.RestaurantRepository;
import com.bhukkad.repository.ReviewRepository;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.service.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReviewServiceImpl implements ReviewService {

    private final ReviewRepository reviewRepository;
    private final OrderRepository orderRepository;
    private final CustomerRepository customerRepository;
    private final RestaurantRepository restaurantRepository;
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

        return review;
    }

    @Override
    public List<Review> getRestaurantReviews(Long restaurantId) {
        return reviewRepository.findByRestaurantIdWithDetails(restaurantId);
    }

    @Override
    public List<Review> getCustomerReviews() {
        Long customerId = securityUtils.getCurrentUserId();
        return reviewRepository.findByCustomerIdWithDetails(customerId);
    }

    @Override
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

    private void updateRestaurantRating(Restaurant restaurant) {
        Double averageRating = reviewRepository.getAverageRatingByRestaurant(restaurant.getId());
        Long totalReviews = reviewRepository.countByRestaurant(restaurant.getId());

        restaurant.setAverageRating(averageRating != null ? averageRating : 0.0);
        restaurant.setTotalReviews(totalReviews.intValue());
        restaurantRepository.save(restaurant);
    }
}