package com.bhukkad.controller;

import com.bhukkad.config.ApiPaths;

import com.bhukkad.dto.request.ReviewRequest;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.entity.Review;
import com.bhukkad.service.ReviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping(ApiPaths.V1_PREFIX + "/reviews")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    @PostMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<Review>> createReview(@Valid @RequestBody ReviewRequest request) {
        Review review = reviewService.createReview(request);
        return ResponseEntity.ok(ApiResponse.success("Review submitted successfully", review));
    }

    @GetMapping("/restaurant/{restaurantId}")
    public ResponseEntity<ApiResponse<List<Review>>> getRestaurantReviews(@PathVariable Long restaurantId) {
        List<Review> reviews = reviewService.getRestaurantReviews(restaurantId);
        return ResponseEntity.ok(ApiResponse.success(reviews));
    }

    @GetMapping("/my-reviews")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<List<Review>>> getMyReviews() {
        List<Review> reviews = reviewService.getCustomerReviews();
        return ResponseEntity.ok(ApiResponse.success(reviews));
    }

    @GetMapping("/order/{orderId}")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<Review>> getReviewByOrderId(@PathVariable Long orderId) {
        Review review = reviewService.getReviewByOrderId(orderId);
        return ResponseEntity.ok(ApiResponse.success(review));
    }

    @DeleteMapping("/{reviewId}")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<Void>> deleteReview(@PathVariable Long reviewId) {
        reviewService.deleteReview(reviewId);
        return ResponseEntity.ok(ApiResponse.success("Review deleted successfully", null));
    }
}