package com.bhukkad.controller;

import com.bhukkad.config.ApiPaths;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.SurveyRatingsResponse;
import com.bhukkad.dto.response.TrendingDishResponse;
import com.bhukkad.entity.DeliverySurvey;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.survey.SurveyService;
import com.bhukkad.survey.TrendingDishService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Post-delivery satisfaction surveys and real-time trending dishes.
 *
 * <p>Survey submission is customer-only; the survey-ratings summary and the
 * trending dish list are public (they match the existing public matchers for
 * {@code /restaurants/public/**} and {@code /home/**}).
 */
@RestController
@RequestMapping(ApiPaths.V1_PREFIX)
@RequiredArgsConstructor
@Tag(name = "Survey", description = "Post-delivery satisfaction surveys and trending dishes")
public class SurveyController {

    private final SurveyService surveyService;
    private final TrendingDishService trendingDishService;
    private final SecurityUtils securityUtils;

    @PostMapping("/reviews/survey")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Submit post-delivery satisfaction survey")
    public ResponseEntity<ApiResponse<DeliverySurvey>> submitSurvey(@RequestBody Map<String, Object> body) {
        Long orderId = asLong(body.get("orderId"));
        Integer ratingDelivery = asRating(body.get("ratingDelivery"));
        Integer ratingFood = asRating(body.get("ratingFood"));
        Integer ratingSpeed = asRating(body.get("ratingSpeed"));
        String comment = body.get("comment") != null ? String.valueOf(body.get("comment")) : null;

        DeliverySurvey survey = surveyService.submitSurvey(
                securityUtils.getCurrentUserId(), orderId,
                ratingDelivery, ratingFood, ratingSpeed, comment);
        return ResponseEntity.ok(ApiResponse.success("Survey submitted successfully", survey));
    }

    @GetMapping("/restaurants/public/{restaurantId}/survey-ratings")
    @Operation(summary = "Get average survey ratings for a restaurant")
    public ResponseEntity<ApiResponse<SurveyRatingsResponse>> getSurveyRatings(
            @PathVariable @Positive Long restaurantId) {
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

    private Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private Integer asRating(Object value) {
        if (!(value instanceof Number number)) {
            return null;
        }
        return number.intValue();
    }
}
