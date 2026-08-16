package com.bhukkad.cache;

import com.bhukkad.cache.CacheKeyGenerator;
import com.bhukkad.dto.response.RestaurantResponse;
import com.bhukkad.entity.Cuisine;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.repository.MenuItemRepository;
import com.bhukkad.repository.RestaurantRepository;
import com.bhukkad.service.MenuService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pre-loads hot cache entries on application startup so that the first user
 * requests after a deploy hit warm caches instead of the database.
 *
 * <p>Currently warms:
 * <ul>
 *   <li>Active restaurant list</li>
 *   <li>Top 20 popular restaurants by rating</li>
 *   <li>Menu categories for the top 20 restaurants</li>
 *   <li>Menu items for the top 20 restaurants</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheWarmingService implements ApplicationRunner {

    private final RestaurantRepository restaurantRepository;
    private final MenuItemRepository menuItemRepository;
    private final MenuService menuService;
    private final RedisCacheService cacheService;

    @Override
    public void run(ApplicationArguments args) {
        warmRestaurantCaches();
        warmMenuCaches();
    }

    void warmRestaurantCaches() {
        log.info("CACHE_WARMING_START restaurants");

        // Warm active restaurant list
        List<RestaurantResponse> allActive = restaurantRepository.findAllActiveWithDetails()
                .stream()
                .map(this::mapToResponse)
                .toList();
        cacheService.set(CacheKeyGenerator.restaurantList(), allActive, 600);

        // Warm top 10 popular restaurants by rating
        List<RestaurantResponse> topRated = restaurantRepository.findTop10ByIsActiveTrueOrderByAverageRatingDesc()
                .stream()
                .map(this::mapToResponse)
                .toList();
        for (RestaurantResponse restaurant : topRated) {
            cacheService.set(CacheKeyGenerator.restaurant(restaurant.getId()), restaurant, 1800);
        }

        log.info("CACHE_WARMING_COMPLETE restaurants count={}", topRated.size());
    }

    void warmMenuCaches() {
        log.info("CACHE_WARMING_START menu");

        // Warm menu categories for the top 10 restaurants
        List<RestaurantResponse> topRated = restaurantRepository.findTop10ByIsActiveTrueOrderByAverageRatingDesc()
                .stream()
                .map(this::mapToResponse)
                .toList();

        for (RestaurantResponse restaurant : topRated) {
            try {
                menuService.getCategoriesByRestaurant(restaurant.getId());
                menuService.getMenuItemsByRestaurant(restaurant.getId());
                menuService.getBestsellers(restaurant.getId());
                menuService.getRecommended(restaurant.getId());
            } catch (Exception ex) {
                log.warn("CACHE_WARMING_FAILED restaurantId={} error={}", restaurant.getId(), ex.getMessage());
            }
        }

        log.info("CACHE_WARMING_COMPLETE menu restaurants={}", topRated.size());
    }

    private RestaurantResponse mapToResponse(Restaurant restaurant) {
        Set<String> cuisineNames = new HashSet<>();
        if (restaurant.getCuisines() != null) {
            cuisineNames = restaurant.getCuisines().stream()
                    .map(Cuisine::getName)
                    .collect(Collectors.toSet());
        }

        return RestaurantResponse.builder()
                .id(restaurant.getId())
                .name(restaurant.getName())
                .description(restaurant.getDescription())
                .cuisines(cuisineNames)
                .imageUrl(restaurant.getImageUrl())
                .openingTime(restaurant.getOpeningTime())
                .closingTime(restaurant.getClosingTime())
                .isOpen(restaurant.getIsOpen())
                .isActive(restaurant.getIsActive())
                .averageRating(restaurant.getAverageRating())
                .totalReviews(restaurant.getTotalReviews())
                .averageDeliveryTime(restaurant.getAverageDeliveryTime())
                .minimumOrderAmount(restaurant.getMinimumOrderAmount())
                .deliveryFee(restaurant.getDeliveryFee())
                .freeDeliveryAvailable(restaurant.getFreeDeliveryAvailable())
                .freeDeliveryAbove(restaurant.getFreeDeliveryAbove())
                .isPureVeg(restaurant.getIsPureVeg())
                .features(restaurant.getFeatures())
                .virtualBrandName(restaurant.getVirtualBrandName())
                .build();
    }
}
