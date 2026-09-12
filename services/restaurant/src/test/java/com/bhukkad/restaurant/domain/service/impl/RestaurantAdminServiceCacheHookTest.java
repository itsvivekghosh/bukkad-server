package com.bhukkad.restaurant.domain.service.impl;

import com.bhukkad.restaurant.domain.entity.Restaurant;
import com.bhukkad.restaurant.domain.repository.RestaurantRepository;
import com.bhukkad.restaurant.infrastructure.cache.MenuCacheInvalidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.bhukkad.restaurant.domain.event.RestaurantEventPublisher;

/**
 * PERF-3 item 2: restaurant activation changes must publish feed-cache
 * invalidation (hook in the restaurant write service of this module).
 */
@ExtendWith(MockitoExtension.class)
class RestaurantAdminServiceCacheHookTest {

    @Mock private RestaurantRepository restaurantRepository;
    @Mock private RestaurantEventPublisher eventPublisher;
    @Mock private com.bhukkad.restaurant.domain.event.MenuEventsPublisher menuEventsPublisher;
    @Mock private MenuCacheInvalidator cacheInvalidator;
    @InjectMocks private RestaurantAdminService service;

    @Test
    void updateAvailability_invalidatesFeedCache() {
        Restaurant restaurant = new Restaurant();
        restaurant.setId(9L);
        restaurant.setIsActive(true);
        when(restaurantRepository.findById(9L)).thenReturn(Optional.of(restaurant));
        when(restaurantRepository.save(restaurant)).thenReturn(restaurant);

        service.updateAvailability(9L, false);

        verify(cacheInvalidator).invalidateFeed();
    }

    @Test
    void create_invalidatesFeedCache() {
        when(restaurantRepository
                .findByNameContainingIgnoreCaseAndIsActiveTrue("New Place")).thenReturn(List.of());
        when(restaurantRepository.save(org.mockito.ArgumentMatchers.any(Restaurant.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.create("New Place", null, 1L, null, null);

        verify(cacheInvalidator).invalidateFeed();
    }
}
