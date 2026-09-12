package com.bhukkad.restaurant.api;

import com.bhukkad.common.security.PrincipalGuard;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.restaurant.domain.MenuItem;
import com.bhukkad.restaurant.domain.MenuItemRepository;
import com.bhukkad.restaurant.domain.Review;
import com.bhukkad.restaurant.service.ReviewService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Review endpoints (port of monolith {@code ReviewController} +
 * {@code AdminReviewModerationController}).
 *
 * <p>The review author is the authenticated principal (no client-supplied
 * customer ids), the public listing exposes only APPROVED reviews without
 * customer ids, and moderation is ADMIN-only.</p>
 */
@RestController
@RequestMapping("/api/v1")
public class ReviewController {

    private final ReviewService reviewService;
    private final MenuItemRepository menuItemRepository;

    public ReviewController(ReviewService reviewService, MenuItemRepository menuItemRepository) {
        this.reviewService = reviewService;
        this.menuItemRepository = menuItemRepository;
    }

    public record ReviewRequest(
            @NotNull Long restaurantId,
            @Min(1) @Max(5) int rating,
            String comment) {}

    public record PublicReviewResponse(Long id, Long restaurantId, int rating, String comment,
                                       String status, java.time.LocalDateTime createdAt) {}

    @PostMapping("/reviews")
    public Review submit(@AuthenticationPrincipal TokenPrincipal principal,
                         @Valid @RequestBody ReviewRequest request) {
        PrincipalGuard.requireAuthenticated(principal);
        return reviewService.submit(request.restaurantId(), principal.userId(),
                request.rating(), request.comment());
    }

    @GetMapping("/restaurants/{restaurantId}/reviews")
    public List<PublicReviewResponse> byRestaurant(@PathVariable Long restaurantId,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Pageable pageable = PageRequest.of(safePage, safeSize);
        return reviewService.approvedByRestaurant(restaurantId, pageable).stream()
                .map(r -> new PublicReviewResponse(r.getId(), r.getRestaurantId(), r.getRating(),
                        r.getComment(), r.getStatus(), r.getCreatedAt()))
                .toList();
    }

    /**
     * Monolith-parity alias used by the customer app for the public review
     * list; identical projection to the canonical
     * {@code /restaurants/{id}/reviews} view.
     */
    @GetMapping("/reviews/restaurant/{restaurantId}")
    public List<PublicReviewResponse> byRestaurantAlias(@PathVariable Long restaurantId,
                                                        @RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "20") int size) {
        return byRestaurant(restaurantId, page, size);
    }

    /** The caller's own reviews (any moderation status). */
    @GetMapping("/reviews/my-reviews")
    public List<PublicReviewResponse> myReviews(@AuthenticationPrincipal TokenPrincipal principal,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        PrincipalGuard.requireAuthenticated(principal);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        return reviewService.byCustomer(principal.userId(), PageRequest.of(safePage, safeSize)).stream()
                .map(r -> new PublicReviewResponse(r.getId(), r.getRestaurantId(), r.getRating(),
                        r.getComment(), r.getStatus(), r.getCreatedAt()))
                .toList();
    }

    /** Customer rating for one menu item from a delivered order. */
    public record MenuItemRatingRequest(@NotNull Long orderId, @NotNull Long menuItemId,
                                        @Min(1) @Max(5) int rating, String comment) {}

    @PostMapping("/reviews/menu-items")
    public PublicReviewResponse rateMenuItem(@AuthenticationPrincipal TokenPrincipal principal,
                                             @Valid @RequestBody MenuItemRatingRequest request) {
        PrincipalGuard.requireAuthenticated(principal);
        MenuItem item = menuItemRepository.findById(request.menuItemId())
                .orElseThrow(() -> new com.bhukkad.common.error.ResourceNotFoundException(
                        "Menu item not found: " + request.menuItemId()));
        Review review = reviewService.submit(item.getRestaurantId(), principal.userId(),
                request.rating(), request.comment());
        return new PublicReviewResponse(review.getId(), review.getRestaurantId(),
                review.getRating(), review.getComment(), review.getStatus(), review.getCreatedAt());
    }

    /**
     * Rating aggregate + approved reviews for a menu item. The dev build
     * derives them from the restaurant's approved reviews (the monolith kept
     * a per-item denormalised counter).
     */
    @GetMapping("/reviews/menu-items/{menuItemId}")
    public java.util.Map<String, Object> menuItemRatings(
            @org.springframework.web.bind.annotation.PathVariable Long menuItemId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        MenuItem item = menuItemRepository.findById(menuItemId)
                .orElseThrow(() -> new com.bhukkad.common.error.ResourceNotFoundException(
                        "Menu item not found: " + menuItemId));
        List<PublicReviewResponse> reviews = byRestaurant(item.getRestaurantId(), page, size);
        double average = reviews.stream().mapToInt(PublicReviewResponse::rating)
                .average().orElse(0.0);
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("menuItemId", menuItemId);
        body.put("restaurantId", item.getRestaurantId());
        body.put("averageRating", Math.round(average * 10.0) / 10.0);
        body.put("totalRatings", reviews.size());
        body.put("reviews", reviews);
        return body;
    }

    @PostMapping("/admin/reviews/{reviewId}/moderate")
    @PreAuthorize("hasRole('ADMIN')")
    public Review moderate(@PathVariable Long reviewId, @RequestParam String status) {
        return reviewService.moderate(reviewId, status);
    }

    /** Customer deletes their own review; ADMIN may delete any (IDOR-safe). */
    @DeleteMapping("/reviews/{reviewId}")
    public java.util.Map<String, Object> deleteReview(@AuthenticationPrincipal TokenPrincipal principal,
                                                      @PathVariable Long reviewId) {
        reviewService.deleteFor(principal, reviewId);
        return java.util.Map.of("id", reviewId, "deleted", true);
    }
}
