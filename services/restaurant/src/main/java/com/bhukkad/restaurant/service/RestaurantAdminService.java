package com.bhukkad.restaurant.service;

import com.bhukkad.common.error.DuplicateRequestException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.restaurant.domain.Restaurant;
import com.bhukkad.restaurant.domain.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Restaurant write operations (port of monolith {@code RestaurantServiceImpl}
 * write surface): create, update, availability.
 */
@Service
@RequiredArgsConstructor
public class RestaurantAdminService {

    private final RestaurantRepository restaurantRepository;
    private final RestaurantEventPublisher eventPublisher;

    @Transactional
    public Restaurant create(String name, String description, Long cuisineId,
                             String address, String phone) {
        List<Restaurant> existing = restaurantRepository.findByNameContainingIgnoreCaseAndIsActiveTrue(name);
        if (!existing.isEmpty()) {
            throw new DuplicateRequestException("Restaurant already exists: " + name);
        }
        Restaurant restaurant = new Restaurant();
        restaurant.setName(name);
        restaurant.setDescription(description);
        restaurant.setCuisineId(cuisineId);
        restaurant.setAddress(address);
        restaurant.setPhone(phone);
        restaurant.setIsActive(true);
        Restaurant saved = restaurantRepository.save(restaurant);
        eventPublisher.restaurantCreated(saved.getId(), name);
        return saved;
    }

    @Transactional
    public Restaurant updateAvailability(Long restaurantId, boolean active) {
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found: " + restaurantId));
        restaurant.setIsActive(active);
        restaurantRepository.save(restaurant);
        eventPublisher.availabilityChanged(restaurantId, active);
        return restaurant;
    }
}