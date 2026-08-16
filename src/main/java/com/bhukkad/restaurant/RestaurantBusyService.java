package com.bhukkad.restaurant;

import com.bhukkad.dto.request.RestaurantBusyModeRequest;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages restaurant busy mode for throttling incoming orders during peak load.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RestaurantBusyService {

    private final RestaurantRepository restaurantRepository;

    /**
     * Enables busy mode for a restaurant with optional duration and extra prep time.
     *
     * @param restaurantId restaurant identifier
     * @param request      busy mode settings
     */
    @Transactional
    public void setBusyMode(Long restaurantId, RestaurantBusyModeRequest request) {
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found"));

        restaurant.setBusyMode(true);
        restaurant.setBusyUntil(request.getBusyUntil());
        if (request.getExtraPrepMinutes() != null) {
            restaurant.setExtraPrepMinutes(request.getExtraPrepMinutes());
        }
        restaurantRepository.save(restaurant);
    }

    /**
     * Asserts the restaurant is accepting new orders (not in active busy mode).
     */
    @Transactional
    public void assertAcceptingOrders(Long restaurantId) {
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found"));

        if (!Boolean.TRUE.equals(restaurant.getBusyMode())) {
            return;
        }
        if (restaurant.getBusyUntil() != null && restaurant.getBusyUntil().isBefore(java.time.LocalDateTime.now())) {
            clearBusyMode(restaurantId);
            return;
        }
        throw new com.bhukkad.exception.BusinessException(
                "Restaurant is in busy mode and not accepting new orders right now");
    }

    /**
     * Clears busy mode and resets extra prep time for a restaurant.
     *
     * @param restaurantId restaurant identifier
     */
    @Transactional
    public void clearBusyMode(Long restaurantId) {
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found"));

        restaurant.setBusyMode(false);
        restaurant.setBusyUntil(null);
        restaurant.setExtraPrepMinutes(0);
        restaurantRepository.save(restaurant);
    }
}
