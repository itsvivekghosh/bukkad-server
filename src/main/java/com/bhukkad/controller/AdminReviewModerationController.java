package com.bhukkad.controller;

import com.bhukkad.config.ApiPaths;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.entity.Review;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.service.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Admin review moderation queue (V17 trust and compliance).
 *
 * <p>Lives under {@code /api/v1/admin/**}, which {@code SecurityConfig} already restricts to
 * {@code ROLE_ADMIN}; the class-level {@link PreAuthorize} is defence in depth for the same rule.
 * Moderation deliberately does <em>not</em> live under {@code /api/v1/reviews/**} because that
 * path family is scoped to {@code ROLE_CUSTOMER} at the filter-chain level.
 *
 * <p>Rejecting a review hides it from public restaurant pages and drops it out of the aggregate
 * rating without destroying the record, which keeps an audit trail that a delete would not.
 */
@RestController
@RequestMapping(ApiPaths.V1_PREFIX + "/admin/reviews")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "AdminReviewModeration", description = "REST endpoints for AdminReviewModeration")
public class AdminReviewModerationController {

    private final ReviewService reviewService;

    /**
     * Lists reviews awaiting or resolved by moderation, oldest first.
     *
     * @param status optional {@code PENDING} (default), {@code APPROVED} or {@code REJECTED}
     */
    @GetMapping("/moderation")
    @Operation(summary = "Get moderation queue")
    public ResponseEntity<ApiResponse<List<Review>>> getModerationQueue(
            @RequestParam(required = false) String status) {
        Review.ModerationStatus parsed = status != null ? parseStatus(status) : null;
        return ResponseEntity.ok(ApiResponse.success(reviewService.getModerationQueue(parsed)));
    }

    /**
     * Approves or rejects a review and recalculates the restaurant's rating.
     *
     * @param status {@code PENDING}, {@code APPROVED} or {@code REJECTED} (case-insensitive)
     */
    @PutMapping("/{reviewId}/moderate")
    @Operation(summary = "Moderate review")
    public ResponseEntity<ApiResponse<Review>> moderateReview(
            @PathVariable Long reviewId,
            @RequestParam String status) {
        Review review = reviewService.moderateReview(reviewId, parseStatus(status));
        return ResponseEntity.ok(ApiResponse.success("Review moderation updated", review));
    }

    /**
     * Converts the request parameter to a {@link Review.ModerationStatus}, reporting the accepted
     * values on failure so the caller does not have to guess. Throwing
     * {@link BusinessException} maps to HTTP 400 via {@code GlobalExceptionHandler}, whereas an
     * unhandled {@code IllegalArgumentException} would surface as a 500.
     */
    private Review.ModerationStatus parseStatus(String status) {
        try {
            return Review.ModerationStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            String allowed = Arrays.stream(Review.ModerationStatus.values())
                    .map(Enum::name)
                    .collect(Collectors.joining(", "));
            throw new BusinessException("Invalid moderation status: " + status + ". Allowed values: " + allowed);
        }
    }
}
