package com.bhukkad.restaurant.domain.service.impl;

import com.bhukkad.restaurant.domain.entity.Restaurant;
import com.bhukkad.restaurant.domain.repository.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Manages restaurant busy mode for throttling incoming orders during peak load.
 *
 * <p>Port of the monolith {@code com.bhukkad.restaurant.RestaurantBusyService}.
 * In the restaurant service, busy-mode fields live on
 * {@link Restaurant} (busyMode / busyUntil / extraPrepMinutes). The owning
 * controller is {@code restaurant} in the monolith, which keeps a working
 * copy until the gateway flips over.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RestaurantBusyService {

    private final RestaurantRepository restaurantRepository;

    /**
     * Enables busy mode for a restaurant with optional duration and extra prep time.
     */
    @Transactional
    public void setBusyMode(Long restaurantId, LocalDateTime busyUntil, Integer extraPrepMinutes) {
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new IllegalArgumentException("Restaurant not found: " + restaurantId));

        restaurant.setBusyMode(true);
        restaurant.setBusyUntil(busyUntil);
        if (extraPrepMinutes != null) {
            restaurant.setExtraPrepMinutes(extraPrepMinutes);
        }
        restaurantRepository.save(restaurant);
    }

    /**
     * Asserts the restaurant is accepting new orders (not in active busy mode).
     */
    @Transactional
    public void assertAcceptingOrders(Long restaurantId) {
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new IllegalArgumentException("Restaurant not found: " + restaurantId));

        if (!Boolean.TRUE.equals(restaurant.getBusyMode())) {
            return;
        }
        if (restaurant.getBusyUntil() != null && restaurant.getBusyUntil().isBefore(LocalDateTime.now())) {
            clearBusyMode(restaurantId);
            return;
        }
        throw new IllegalStateException(
                "Restaurant is in busy mode and not accepting new orders right now");
    }

    /**
     * Clears busy mode and resets extra prep time for a restaurant.
     */
    @Transactional
    public void clearBusyMode(Long restaurantId) {
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new IllegalArgumentException("Restaurant not found: " + restaurantId));

        restaurant.setBusyMode(false);
        restaurant.setBusyUntil(null);
        restaurant.setExtraPrepMinutes(0);
        restaurantRepository.save(restaurant);
    }
}