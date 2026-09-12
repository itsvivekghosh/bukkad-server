package com.bhukkad.personalization.api.controller;

import com.bhukkad.common.error.UnauthorizedException;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.personalization.api.dto.response.FeedRankResponse;
import com.bhukkad.personalization.api.dto.response.RecommendationResponse;
import com.bhukkad.personalization.domain.service.RecommendationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Recommendation surface. The subject is ALWAYS the authenticated principal:
 * the previous {@code X-Customer-Id} header contract was client-spoofable and
 * leaked other customers' order-history-derived preferences.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/customers/me/recommendations")
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendationService recommendationService;

    @GetMapping("/reorder")
    public ResponseEntity<List<RecommendationResponse>> reorderSuggestions(
            @AuthenticationPrincipal TokenPrincipal principal) {
        Long customerId = requireCustomerId(principal);
        log.debug("Reorder suggestions for customer {}", customerId);
        return ResponseEntity.ok(recommendationService.reorderSuggestions(customerId));
    }

    @GetMapping("/for-you")
    public ResponseEntity<List<RecommendationResponse>> collaborativeSuggestions(
            @AuthenticationPrincipal TokenPrincipal principal) {
        Long customerId = requireCustomerId(principal);
        log.debug("Collaborative suggestions for customer {}", customerId);
        return ResponseEntity.ok(recommendationService.collaborativeSuggestions(customerId));
    }

    @GetMapping("/time-aware")
    public ResponseEntity<List<RecommendationResponse>> timeAwareSuggestions(
            @AuthenticationPrincipal TokenPrincipal principal) {
        Long customerId = requireCustomerId(principal);
        log.debug("Time-aware suggestions for customer {}", customerId);
        return ResponseEntity.ok(recommendationService.timeAwareSuggestions(customerId));
    }

    @GetMapping("/feed-rank")
    public ResponseEntity<FeedRankResponse> rankFeed(
            @AuthenticationPrincipal TokenPrincipal principal,
            @RequestParam("restaurantIds") List<Long> restaurantIds) {
        Long customerId = requireCustomerId(principal);
        if (restaurantIds == null || restaurantIds.isEmpty() || restaurantIds.size() > 100) {
            return ResponseEntity.badRequest().build();
        }
        log.debug("Feed rank for customer {} with {} restaurants", customerId, restaurantIds.size());
        return ResponseEntity.ok(recommendationService.rankRestaurantsForCustomer(customerId, restaurantIds));
    }

    private static Long requireCustomerId(TokenPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            throw new UnauthorizedException("Authenticated customer required");
        }
        return principal.userId();
    }
}
