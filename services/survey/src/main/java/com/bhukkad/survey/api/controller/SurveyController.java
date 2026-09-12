package com.bhukkad.survey.api.controller;

import com.bhukkad.survey.domain.service.SurveyService;
import com.bhukkad.survey.domain.service.TrendingDishService;
import com.bhukkad.survey.api.dto.response.ApiResponse;
import com.bhukkad.survey.api.dto.response.SurveyRatingsResponse;
import com.bhukkad.survey.api.dto.response.TrendingDishResponse;
import com.bhukkad.survey.domain.entity.DeliverySurvey;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Post-delivery satisfaction surveys and real-time trending dishes.
 *
 * <p>Survey submission is customer-only; the survey-ratings summary and the
 * trending dish list are public.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Survey", description = "Post-delivery satisfaction surveys and trending dishes")
public class SurveyController {

    private static final String COMMENT_FIELD = "comment";

    private final SurveyService surveyService;
    private final TrendingDishService trendingDishService;

    public SurveyController(SurveyService surveyService, TrendingDishService trendingDishService) {
        this.surveyService = surveyService;
        this.trendingDishService = trendingDishService;
    }

    @PostMapping("/reviews/survey")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Submit post-delivery satisfaction survey")
    public ResponseEntity<ApiResponse<Map<String, Object>>> submitSurvey(
            @RequestBody Map<String, Object> body, Authentication authentication) {
        Long orderId = asLong(body.get("orderId"));
        Long restaurantId = asLong(body.get("restaurantId"));
        Integer ratingDelivery = asRating(body.get("ratingDelivery"));
        Integer ratingFood = asRating(body.get("ratingFood"));
        Integer ratingSpeed = asRating(body.get("ratingSpeed"));
        String comment = body.get(COMMENT_FIELD) != null ? String.valueOf(body.get(COMMENT_FIELD)) : null;

        DeliverySurvey survey = surveyService.submitSurvey(
                currentUserId(authentication), orderId, restaurantId,
                ratingDelivery, ratingFood, ratingSpeed, comment);

        // Flat projection instead of the JPA entity: keeps the response stable
        // and avoids lazy-association serialization issues after the transaction.
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", survey.getId());
        response.put("orderId", survey.getOrderId());
        response.put("restaurantId", survey.getRestaurantId());
        response.put("ratingDelivery", survey.getRatingDelivery());
        response.put("ratingFood", survey.getRatingFood());
        response.put("ratingSpeed", survey.getRatingSpeed());
        response.put(COMMENT_FIELD, survey.getComment());
        response.put("submittedAt", survey.getSubmittedAt());
        return ResponseEntity.ok(ApiResponse.success("Survey submitted successfully", response));
    }

    @GetMapping("/restaurants/public/{restaurantId}/survey-ratings")
    @Operation(summary = "Get average survey ratings for a restaurant")
    public ResponseEntity<ApiResponse<SurveyRatingsResponse>> getSurveyRatings(
            @PathVariable Long restaurantId) {
        SurveyRatingsResponse ratings = surveyService.getRestaurantRatings(restaurantId);
        return ResponseEntity.ok(ApiResponse.success(ratings));
    }

    @GetMapping("/home/trending")
    @Operation(summary = "Get trending dishes for the home feed")
    public ResponseEntity<ApiResponse<List<TrendingDishResponse>>> getTrendingDishes(
            @RequestParam(defaultValue = "10") int limit) {
        List<TrendingDishResponse> dishes = trendingDishService.trending(limit);
        return ResponseEntity.ok(ApiResponse.success(dishes));
    }

    /**
     * Resolves the caller id from the validated platform JWT principal. The
     * old implementation parsed {@code Authentication.getName()} — which is
     * the {@code TokenPrincipal} record's {@code toString()} — so every
     * submission failed with 401.
     */
    private Long currentUserId(Principal principal) {
        if (principal instanceof Authentication auth
                && auth.getPrincipal() instanceof com.bhukkad.common.security.TokenPrincipal tokenPrincipal) {
            return tokenPrincipal.userId();
        }
        return null;
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        // JSON clients may send ids as numeric strings ("1441"); tolerate both.
        if (value instanceof String s && s.matches("\\d+")) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Integer asRating(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String s && s.matches("\\d+")) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}