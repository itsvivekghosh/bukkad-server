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
    List<RestaurantResponse> getRestaurantsByOwner(Long ownerId);
    RestaurantResponse updateRestaurant(Long id, RestaurantRequest request);
    void deleteRestaurant(Long id);

    // Search and filter
    List<RestaurantResponse> searchRestaurants(String keyword);
    List<RestaurantResponse> filterRestaurants(Long cuisineId, Boolean isPureVeg);
    List<RestaurantResponse> findNearbyRestaurants(double latitude, double longitude, double radiusKm, int limit);

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
