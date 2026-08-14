package com.bhukkad.service;

import com.bhukkad.dto.response.RestaurantAnalyticsResponse;

public interface RestaurantAnalyticsService {
    RestaurantAnalyticsResponse getAnalytics(Long restaurantId, int days);
}
