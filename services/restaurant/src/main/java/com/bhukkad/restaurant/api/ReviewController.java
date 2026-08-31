package com.bhukkad.restaurant.api;

import com.bhukkad.restaurant.domain.Review;
import com.bhukkad.restaurant.service.ReviewService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Review endpoints (port of monolith {@code ReviewController} +
 * {@code AdminReviewModerationController}).
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    public record ReviewRequest(
            @NotNull Long restaurantId,
            @NotNull Long customerId,
            @Min(1) @Max(5) int rating,
            String comment) {}

    @PostMapping("/reviews")
    public Review submit(@RequestBody ReviewRequest request) {
        return reviewService.submit(request.restaurantId(), request.customerId(), request.rating(), request.comment());
    }

    @GetMapping("/restaurants/{restaurantId}/reviews")
    public List<Review> byRestaurant(@PathVariable Long restaurantId) {
        return reviewService.byRestaurant(restaurantId);
    }

    @PostMapping("/admin/reviews/{reviewId}/moderate")
    public Review moderate(@PathVariable Long reviewId, @RequestParam String status) {
        return reviewService.moderate(reviewId, status);
    }
}