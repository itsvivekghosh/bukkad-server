package com.bhukkad.service;

import com.bhukkad.dto.request.ReviewRequest;
import com.bhukkad.entity.Review;

import java.util.List;

public interface ReviewService {
    Review createReview(ReviewRequest request);
    List<Review> getRestaurantReviews(Long restaurantId);
    List<Review> getCustomerReviews();
    Review getReviewByOrderId(Long orderId);
    void deleteReview(Long reviewId);
}