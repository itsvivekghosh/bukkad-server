package com.bhukkad.service;

import com.bhukkad.dto.request.RestaurantRequest;
import com.bhukkad.dto.response.RestaurantOnboardingStatusResponse;
import com.bhukkad.dto.response.RestaurantResponse;

import java.util.List;

public interface RestaurantService {
    RestaurantResponse createRestaurant(RestaurantRequest request);
    RestaurantResponse getRestaurantById(Long id);
    List<RestaurantResponse> getAllActiveRestaurants();
    List<RestaurantResponse> getAllActiveRestaurants(Long tenantId);
    List<RestaurantResponse> getAllActiveRestaurants(Long tenantId, Double latitude, Double longitude, Double radiusKm);
    List<RestaurantResponse> getRestaurantsByOwner(Long ownerId);
    RestaurantResponse updateRestaurant(Long id, RestaurantRequest request);
    void deleteRestaurant(Long id);

    // Search and filter
    List<RestaurantResponse> searchRestaurants(String keyword);
    List<RestaurantResponse> filterRestaurants(Long cuisineId, Boolean isPureVeg);
    java.util.List<RestaurantResponse> getRestaurantsByIds(java.util.List<Long> ids);
    List<RestaurantResponse> findNearbyRestaurants(double latitude, double longitude, double radiusKm, int limit);
    List<RestaurantResponse> getActiveRestaurantsInRadius(Double latitude, Double longitude, Double radiusKm);

    /**
     * Returns the top-rated restaurants within a delivery radius of the given
     * coordinates, sorted by average rating (highest first), then by total
     * reviews as a tiebreaker. Results include {@code distanceKm}.
     *
     * @param latitude  user's latitude
     * @param longitude user's longitude
     * @param radiusKm  search radius in kilometres
     * @param limit     maximum results to return (clamped to [1, 50])
     */
    List<RestaurantResponse> findTopRatedNearbyRestaurants(double latitude, double longitude, double radiusKm, int limit);

    // Status management
    void toggleRestaurantStatus(Long id, Boolean isOpen);
    void updateRestaurantRating(Long restaurantId);

    // Owner operations
    List<RestaurantResponse> getMyRestaurants();

    // ── Dark Kitchen onboarding ──────────────────────────────────────────
    RestaurantResponse createOnboardingApplication(RestaurantRequest request);
    RestaurantOnboardingStatusResponse getOnboardingStatus();
    void reviewOnboarding(Long restaurantId, boolean approved, String reason);
}
