package com.bhukkad.personalization.api;

import com.bhukkad.personalization.dto.FeedRankResponse;
import com.bhukkad.personalization.dto.RecommendationResponse;
import com.bhukkad.personalization.service.RecommendationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/customers/me/recommendations")
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendationService recommendationService;

    @GetMapping("/reorder")
    public ResponseEntity<List<RecommendationResponse>> reorderSuggestions(
            @RequestHeader("X-Customer-Id") Long customerId) {
        log.debug("Reorder suggestions for customer {}", customerId);
        return ResponseEntity.ok(recommendationService.reorderSuggestions(customerId));
    }

    @GetMapping("/for-you")
    public ResponseEntity<List<RecommendationResponse>> collaborativeSuggestions(
            @RequestHeader("X-Customer-Id") Long customerId) {
        log.debug("Collaborative suggestions for customer {}", customerId);
        return ResponseEntity.ok(recommendationService.collaborativeSuggestions(customerId));
    }

    @GetMapping("/time-aware")
    public ResponseEntity<List<RecommendationResponse>> timeAwareSuggestions(
            @RequestHeader("X-Customer-Id") Long customerId) {
        log.debug("Time-aware suggestions for customer {}", customerId);
        return ResponseEntity.ok(recommendationService.timeAwareSuggestions(customerId));
    }

    @GetMapping("/feed-rank")
    public ResponseEntity<FeedRankResponse> rankFeed(
            @RequestHeader("X-Customer-Id") Long customerId,
            @RequestParam("restaurantIds") List<Long> restaurantIds) {
        if (restaurantIds == null || restaurantIds.isEmpty() || restaurantIds.size() > 100) {
            return ResponseEntity.badRequest().build();
        }
        log.debug("Feed rank for customer {} with {} restaurants", customerId, restaurantIds.size());
        return ResponseEntity.ok(recommendationService.rankRestaurantsForCustomer(customerId, restaurantIds));
    }
}
