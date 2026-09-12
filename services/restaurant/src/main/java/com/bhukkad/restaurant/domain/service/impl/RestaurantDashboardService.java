package com.bhukkad.restaurant.domain.service.impl;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.restaurant.domain.entity.Restaurant;
import com.bhukkad.restaurant.domain.repository.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Restaurant owner dashboard 2.0.
 *
 * <p>Restaurant-service port of the monolith
 * {@code com.bhukkad.restaurant.RestaurantDashboardService}. The dashboard
 * aggregates analytics + settlement + ops status. In the restaurant service
 * the analytics/settlement sources are not yet local (they live in the
 * monolith's settlement / admin domains), so this service exposes a minimal
 * local view based on the {@link Restaurant} entity — analytics and
 * settlement aggregation are wired in later batches.
 *
 * <p>The monolith keeps a working copy that drives the legacy controller
 * until the gateway flips the route.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RestaurantDashboardService {

    private final RestaurantRepository restaurantRepository;

    public RestaurantDashboardView getDashboard(Long restaurantId) {
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found: " + restaurantId));
        return new RestaurantDashboardView(
                restaurant.getId(),
                restaurant.getName(),
                Boolean.TRUE.equals(restaurant.getBusyMode()),
                restaurant.getExtraPrepMinutes(),
                restaurant.getBusyUntil());
    }

    @Transactional
    public void assertAcceptingOrders(Long restaurantId) {
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found: " + restaurantId));
        if (Boolean.TRUE.equals(restaurant.getBusyMode())
                && restaurant.getBusyUntil() != null
                && restaurant.getBusyUntil().isAfter(LocalDateTime.now())) {
            throw new BusinessException(
                    "Restaurant is in busy mode and not accepting new orders right now");
        }
    }

    public record RestaurantDashboardView(
            Long restaurantId,
            String name,
            boolean busyMode,
            Integer extraPrepMinutes,
            LocalDateTime busyUntil) {}
}