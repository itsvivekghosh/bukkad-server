package com.bhukkad.controller;

import com.bhukkad.config.ApiPaths;
import com.bhukkad.cache.http.HttpCacheSupport;
import com.bhukkad.dto.request.RestaurantBusyModeRequest;
import com.bhukkad.dto.request.RestaurantRequest;
import com.bhukkad.dto.request.ReviewResponseRequest;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.RestaurantOnboardingStatusResponse;
import com.bhukkad.dto.response.RestaurantResponse;
import com.bhukkad.entity.Review;
import com.bhukkad.ratelimit.RateLimited;
import com.bhukkad.dto.response.PagedResponse;
import com.bhukkad.dto.response.RestaurantSettlementResponse;
import com.bhukkad.dto.response.RestaurantDashboardResponse;
import com.bhukkad.restaurant.RestaurantBusyService;
import com.bhukkad.restaurant.RestaurantDashboardService;
import com.bhukkad.service.RestaurantAnalyticsService;
import com.bhukkad.service.RestaurantService;
import com.bhukkad.service.ReviewService;
import com.bhukkad.settlement.RestaurantSettlementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping(ApiPaths.V1_PREFIX + "/restaurants")
@RequiredArgsConstructor
@Tag(name = "Restaurant", description = "REST endpoints for Restaurant")
public class RestaurantController {

    private final RestaurantService restaurantService;
    private final RestaurantAnalyticsService restaurantAnalyticsService;
    private final RestaurantSettlementService restaurantSettlementService;
    private final RestaurantBusyService restaurantBusyService;
    private final RestaurantDashboardService restaurantDashboardService;
    private final ReviewService reviewService;
    private final HttpCacheSupport httpCacheSupport;

    // Public endpoints
    @GetMapping("/public")
    @Operation(summary = "Get all restaurants")
    public ResponseEntity<ApiResponse<List<RestaurantResponse>>> getAllRestaurants(
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
            @RequestHeader(value = HttpHeaders.IF_MODIFIED_SINCE, required = false) Long ifModifiedSince,
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        List<RestaurantResponse> restaurants = restaurantService.getAllActiveRestaurants(tenantId);
        ApiResponse<List<RestaurantResponse>> body = ApiResponse.success(restaurants);

        HttpHeaders headers = httpCacheSupport.buildCacheHeaders(
                com.bhukkad.cache.CacheKeyGenerator.restaurantList(),
                body.toString());
        String etag = headers.getETag();

        if (httpCacheSupport.isNotModified(ifNoneMatch, etag)) {
            return ResponseEntity.status(304).build();
        }

        return ResponseEntity.ok().headers(headers).body(body);
    }

    @GetMapping("/public/{id}")
    @Operation(summary = "Get restaurant by id")
    public ResponseEntity<ApiResponse<RestaurantResponse>> getRestaurantById(
            @PathVariable Long id,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
            @RequestHeader(value = HttpHeaders.IF_MODIFIED_SINCE, required = false) Long ifModifiedSince) {
        RestaurantResponse restaurant = restaurantService.getRestaurantById(id);
        ApiResponse<RestaurantResponse> body = ApiResponse.success(restaurant);

        HttpHeaders headers = httpCacheSupport.buildCacheHeaders(
                com.bhukkad.cache.CacheKeyGenerator.restaurant(id),
                body.toString());
        String etag = headers.getETag();

        if (httpCacheSupport.isNotModified(ifNoneMatch, etag)) {
            return ResponseEntity.status(304).build();
        }

        return ResponseEntity.ok().headers(headers).body(body);
    }

    @GetMapping("/public/search")
    @RateLimited("search")
    public ResponseEntity<ApiResponse<List<RestaurantResponse>>> searchRestaurants(@RequestParam String keyword) {
        List<RestaurantResponse> restaurants = restaurantService.searchRestaurants(keyword);
        return ResponseEntity.ok(ApiResponse.success(restaurants));
    }

    @GetMapping("/public/nearby")
    @RateLimited("search")
    @Operation(summary = "Find nearby restaurants")
    public ResponseEntity<ApiResponse<List<RestaurantResponse>>> findNearbyRestaurants(
            @RequestParam double latitude,
            @RequestParam double longitude,
            @RequestParam(defaultValue = "5") double radiusKm,
            @RequestParam(defaultValue = "20") int limit) {
        List<RestaurantResponse> restaurants = restaurantService.findNearbyRestaurants(
                latitude, longitude, radiusKm, limit);
        return ResponseEntity.ok(ApiResponse.success(restaurants));
    }

    @GetMapping("/public/filter")
    @Operation(summary = "Filter restaurants")
    public ResponseEntity<ApiResponse<List<RestaurantResponse>>> filterRestaurants(
            @RequestParam(required = false) Long cuisineId,
            @RequestParam(required = false) Boolean isPureVeg) {
        List<RestaurantResponse> restaurants = restaurantService.filterRestaurants(cuisineId, isPureVeg);
        return ResponseEntity.ok(ApiResponse.success(restaurants));
    }

    // Owner endpoints
    @PostMapping("/owner")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    @Operation(summary = "Create restaurant")
    public ResponseEntity<ApiResponse<RestaurantResponse>> createRestaurant(
            @Valid @RequestBody RestaurantRequest request) {
        RestaurantResponse restaurant = restaurantService.createRestaurant(request);
        return ResponseEntity.ok(ApiResponse.success("Restaurant created successfully", restaurant));
    }

    /**
     * Self-serve dark kitchen onboarding: submits an application for verification.
     * The restaurant starts in {@code PENDING_VERIFICATION} until an admin approves it.
     */
    @PostMapping("/onboarding/signup")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    @Operation(summary = "Onboarding signup")
    public ResponseEntity<ApiResponse<RestaurantResponse>> onboardingSignup(
            @Valid @RequestBody RestaurantRequest request) {
        RestaurantResponse restaurant = restaurantService.createOnboardingApplication(request);
        return ResponseEntity.ok(ApiResponse.success(
                "Onboarding application submitted for verification", restaurant));
    }

    /** Returns the onboarding status of all restaurants owned by the caller. */
    @GetMapping("/onboarding/status")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    public ResponseEntity<ApiResponse<RestaurantOnboardingStatusResponse>> onboardingStatus() {
        return ResponseEntity.ok(ApiResponse.success(restaurantService.getOnboardingStatus()));
    }

    @GetMapping("/owner/my-restaurants")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    public ResponseEntity<ApiResponse<List<RestaurantResponse>>> getMyRestaurants() {
        List<RestaurantResponse> restaurants = restaurantService.getMyRestaurants();
        return ResponseEntity.ok(ApiResponse.success(restaurants));
    }

    @PutMapping("/owner/{id}")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    @Operation(summary = "Update restaurant")
    public ResponseEntity<ApiResponse<RestaurantResponse>> updateRestaurant(
            @PathVariable Long id,
            @Valid @RequestBody RestaurantRequest request) {
        RestaurantResponse restaurant = restaurantService.updateRestaurant(id, request);
        return ResponseEntity.ok(ApiResponse.success("Restaurant updated successfully", restaurant));
    }

    @DeleteMapping("/owner/{id}")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    public ResponseEntity<ApiResponse<Void>> deleteRestaurant(@PathVariable Long id) {
        restaurantService.deleteRestaurant(id);
        return ResponseEntity.ok(ApiResponse.success("Restaurant deleted successfully", null));
    }

    @GetMapping("/owner/{id}/analytics")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    @Operation(summary = "Get restaurant analytics")
    public ResponseEntity<ApiResponse<com.bhukkad.dto.response.RestaurantAnalyticsResponse>> getRestaurantAnalytics(
            @PathVariable Long id,
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(ApiResponse.success(
                restaurantAnalyticsService.getAnalytics(id, days)));
    }

    @PutMapping("/owner/{id}/toggle-status")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    @Operation(summary = "Toggle restaurant status")
    public ResponseEntity<ApiResponse<Void>> toggleRestaurantStatus(
            @PathVariable Long id,
            @RequestParam Boolean isOpen) {
        restaurantService.toggleRestaurantStatus(id, isOpen);
        return ResponseEntity.ok(ApiResponse.success("Restaurant status updated", null));
    }

    @GetMapping("/owner/{id}/settlements")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    @Operation(summary = "Get settlements")
    public ResponseEntity<ApiResponse<PagedResponse<RestaurantSettlementResponse>>> getSettlements(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(
                restaurantSettlementService.getRestaurantSettlements(id, page, size)));
    }

    /**
     * Cursor-paginated settlement history. Preferred over the offset variant
     * for restaurants with many historical orders — keeps query cost constant
     * regardless of scroll depth.
     */
    @GetMapping("/owner/{id}/settlements/cursor")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    @Operation(summary = "Get settlements by cursor")
    public ResponseEntity<ApiResponse<com.bhukkad.dto.response.CursorPagedResponse<RestaurantSettlementResponse>>> getSettlementsByCursor(
            @PathVariable Long id,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(
                restaurantSettlementService.getRestaurantSettlementsByCursor(id, cursor, size)));
    }

    /** Enables busy mode to throttle incoming orders during peak hours. */
    @PutMapping("/owner/{id}/busy-mode")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    @Operation(summary = "Enable busy mode")
    public ResponseEntity<ApiResponse<Void>> enableBusyMode(
            @PathVariable Long id,
            @RequestBody RestaurantBusyModeRequest request) {
        restaurantBusyService.setBusyMode(id, request);
        return ResponseEntity.ok(ApiResponse.success("Busy mode enabled", null));
    }

    /** Disables busy mode and restores normal order acceptance. */
    @DeleteMapping("/owner/{id}/busy-mode")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    public ResponseEntity<ApiResponse<Void>> disableBusyMode(@PathVariable Long id) {
        restaurantBusyService.clearBusyMode(id);
        return ResponseEntity.ok(ApiResponse.success("Busy mode cleared", null));
    }

    /** Restaurant dashboard 2.0 with analytics, settlements, and ops status (V16). */
    @GetMapping("/owner/{id}/dashboard")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    @Operation(summary = "Get dashboard")
    public ResponseEntity<ApiResponse<RestaurantDashboardResponse>> getDashboard(
            @PathVariable Long id,
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(ApiResponse.success(restaurantDashboardService.getDashboard(id, days)));
    }

    /**
     * Publishes (or clears) the restaurant owner's public reply to a customer review (V17).
     *
     * <p>Ownership is enforced in {@code ReviewServiceImpl.respondToReview}: the review's
     * restaurant owner must match the authenticated user, otherwise a {@code BusinessException}
     * (HTTP 400) is raised. Sending a blank body is rejected by {@code @NotBlank}; to remove an
     * existing reply the review must be re-moderated by an admin.
     *
     * @param reviewId review being replied to
     * @param request  reply text, max 2000 characters
     * @return the updated review including {@code ownerResponse}
     */
    @PostMapping("/owner/reviews/{reviewId}/response")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    @Operation(summary = "Respond to review")
    public ResponseEntity<ApiResponse<Review>> respondToReview(
            @PathVariable Long reviewId,
            @Valid @RequestBody ReviewResponseRequest request) {
        Review review = reviewService.respondToReview(reviewId, request.getResponse());
        return ResponseEntity.ok(ApiResponse.success("Response added to review", review));
    }
}