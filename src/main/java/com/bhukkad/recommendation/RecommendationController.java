package com.bhukkad.recommendation;

import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.mapper.MenuItemMapper;
import com.bhukkad.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Personalized recommendation endpoints (FEATURE #4).
 *
 * <p>Mounted under {@code /customers/**} so the security chain already requires
 * the CUSTOMER role — recommendations are inherently per-user and anonymous
 * callers get the standard home feed instead.</p>
 */
@RestController
@RequestMapping("/api/v1/customers/me/recommendations")
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendationService recommendationService;
    private final SecurityUtils securityUtils;
    private final MenuItemMapper menuItemMapper;

    @GetMapping("/reorder")
    public ResponseEntity<ApiResponse<?>> reorderSuggestions() {
        return ResponseEntity.ok(ApiResponse.success(
                recommendationService.reorderSuggestions(securityUtils.getCurrentUserId())
                        .stream().map(menuItemMapper::toResponse).toList()));
    }

    /** "Customers who ordered what you order, also ordered…" picks. */
    @GetMapping("/for-you")
    public ResponseEntity<ApiResponse<?>> collaborativeSuggestions() {
        return ResponseEntity.ok(ApiResponse.success(
                recommendationService.collaborativeSuggestions(securityUtils.getCurrentUserId())
                        .stream().map(menuItemMapper::toResponse).toList()));
    }

    /** Meal-window aware picks (breakfast / lunch / snacks / dinner). */
    @GetMapping("/time-aware")
    public ResponseEntity<ApiResponse<?>> timeAwareSuggestions() {
        return ResponseEntity.ok(ApiResponse.success(
                recommendationService.timeAwareSuggestions(securityUtils.getCurrentUserId())
                        .stream().map(menuItemMapper::toResponse).toList()));
    }

    /**
     * Re-ranks a caller-supplied candidate restaurant list by the customer's
     * ordering affinity — used by mobile clients to personalize their feed.
     */
    @GetMapping("/feed-rank")
    public ResponseEntity<ApiResponse<Map<String, List<Long>>>> rankFeed(
            @org.springframework.web.bind.annotation.RequestParam("restaurantIds") List<Long> restaurantIds) {
        if (restaurantIds == null || restaurantIds.isEmpty() || restaurantIds.size() > 100) {
            return ResponseEntity.badRequest().body(ApiResponse.error("restaurantIds must contain 1..100 ids"));
        }
        List<Long> ranked = recommendationService.rankRestaurantsForCustomer(
                securityUtils.getCurrentUserId(), restaurantIds);
        return ResponseEntity.ok(ApiResponse.success(Map.of("rankedRestaurantIds", ranked)));
    }
}
