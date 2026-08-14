package com.bhukkad.serviceImpl;

import com.bhukkad.cache.CacheKeyGenerator;
import com.bhukkad.cache.RedisCacheService;
import com.bhukkad.datasource.UseReadReplica;
import com.bhukkad.dto.request.RestaurantRequest;
import com.bhukkad.mapper.AddressMapper;
import com.bhukkad.dto.response.AddressResponse;
import com.bhukkad.dto.response.RestaurantResponse;
import com.bhukkad.entity.Address;
import com.bhukkad.entity.Cuisine;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.entity.RestaurantOwner;
import com.bhukkad.entity.User;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.exception.UnauthorizedException;
import com.bhukkad.repository.CuisineRepository;
import com.bhukkad.repository.RestaurantOwnerRepository;
import com.bhukkad.repository.RestaurantRepository;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.service.RestaurantService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RestaurantServiceImpl implements RestaurantService {

    private static final Logger log = LoggerFactory.getLogger(RestaurantServiceImpl.class);

    private final RestaurantRepository restaurantRepository;
    private final RestaurantOwnerRepository restaurantOwnerRepository;
    private final CuisineRepository cuisineRepository;
    private final SecurityUtils securityUtils;
    private final RedisCacheService cacheService;
    private final AddressMapper addressMapper;

    @Value("${cache.ttl.restaurant:1800}")
    private long restaurantTtl;

    @Value("${cache.ttl.restaurant-list:600}")
    private long restaurantListTtl;

    @Value("${cache.ttl.search:300}")
    private long searchTtl;

    @Override
    @UseReadReplica
    public RestaurantResponse getRestaurantById(Long id) {
        String cacheKey = CacheKeyGenerator.restaurant(id);

        Optional<RestaurantResponse> cached = cacheService.get(cacheKey, RestaurantResponse.class);
        if (cached.isPresent()) return cached.get();

        Restaurant restaurant = restaurantRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found"));

        RestaurantResponse response = mapToResponse(restaurant);
        cacheService.set(cacheKey, response, restaurantTtl);
        return response;
    }

    @Override
    @UseReadReplica
    public List<RestaurantResponse> getAllActiveRestaurants() {
        String cacheKey = CacheKeyGenerator.restaurantList();
        return cacheService.getListOrCompute(cacheKey, RestaurantResponse.class, restaurantListTtl, () ->
                restaurantRepository.findAllActiveWithDetails()
                        .stream()
                        .map(this::mapToResponse)
                        .collect(Collectors.toList()));
    }

    @Override
    @UseReadReplica
    public List<RestaurantResponse> getMyRestaurants() {
        Long ownerId = securityUtils.getCurrentUserId();
        String cacheKey = CacheKeyGenerator.restaurantsByOwner(ownerId);

        Optional<List<RestaurantResponse>> cached = cacheService.getList(cacheKey, RestaurantResponse.class);
        if (cached.isPresent()) return cached.get();

        List<RestaurantResponse> restaurants = restaurantRepository.findByOwnerIdWithDetails(ownerId)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());

        cacheService.set(cacheKey, restaurants, restaurantListTtl);
        return restaurants;
    }

    @Override
    @UseReadReplica
    public List<RestaurantResponse> getRestaurantsByOwner(Long ownerId) {
        return restaurantRepository.findByOwnerIdWithDetails(ownerId)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    @UseReadReplica
    public List<RestaurantResponse> searchRestaurants(String keyword) {
        String cacheKey = CacheKeyGenerator.restaurantSearch(keyword);
        return cacheService.getListOrCompute(cacheKey, RestaurantResponse.class, searchTtl, () -> {
            List<Restaurant> results;
            try {
                results = restaurantRepository.fullTextSearchByName(keyword.trim());
                if (results.isEmpty()) {
                    results = restaurantRepository.searchByNameWithDetails(keyword);
                }
            } catch (Exception ex) {
                log.debug("RESTAURANT_FULLTEXT_FALLBACK | keyword={}", keyword);
                results = restaurantRepository.searchByNameWithDetails(keyword);
            }
            return results.stream()
                    .map(restaurant -> restaurantRepository.findByIdWithDetails(restaurant.getId()).orElse(restaurant))
                    .map(this::mapToResponse)
                    .collect(Collectors.toList());
        });
    }

    @Override
    @UseReadReplica
    public List<RestaurantResponse> findNearbyRestaurants(
            double latitude, double longitude, double radiusKm, int limit) {
        String cacheKey = CacheKeyGenerator.restaurantNearby(latitude, longitude, radiusKm);
        int safeLimit = Math.min(Math.max(limit, 1), 50);
        double safeRadius = Math.min(Math.max(radiusKm, 0.5), 50.0);
        return cacheService.getListOrCompute(cacheKey, RestaurantResponse.class, searchTtl, () ->
                restaurantRepository.findNearbyRestaurantIds(latitude, longitude, safeRadius, safeLimit)
                        .stream()
                        .map(id -> restaurantRepository.findByIdWithDetails(id).orElse(null))
                        .filter(Objects::nonNull)
                        .map(this::mapToResponse)
                        .collect(Collectors.toList()));
    }

    @Override
    @UseReadReplica
    public List<RestaurantResponse> filterRestaurants(Long cuisineId, Boolean isPureVeg) {
        String cacheKey = CacheKeyGenerator.restaurantFilter(cuisineId, isPureVeg);
        return cacheService.getListOrCompute(cacheKey, RestaurantResponse.class, searchTtl, () ->
                restaurantRepository.findByFilters(cuisineId, isPureVeg)
                        .stream()
                        .map(this::mapToResponseSafe)
                        .collect(Collectors.toList()));
    }

    @Override
    public RestaurantResponse createRestaurant(RestaurantRequest request) {
        Long ownerId = securityUtils.getCurrentUserId();

        RestaurantOwner owner = restaurantOwnerRepository.findById(ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Owner not found"));

        Restaurant restaurant = new Restaurant();
        restaurant.setOwner(owner);
        restaurant.setName(request.getName());
        restaurant.setDescription(request.getDescription());
        restaurant.setImageUrl(request.getImageUrl());
        restaurant.setOpeningTime(request.getOpeningTime());
        restaurant.setClosingTime(request.getClosingTime());
        restaurant.setAverageDeliveryTime(request.getAverageDeliveryTime());
        restaurant.setMinimumOrderAmount(request.getMinimumOrderAmount());
        restaurant.setDeliveryFee(request.getDeliveryFee());
        restaurant.setFreeDeliveryAvailable(request.getFreeDeliveryAvailable() != null ? request.getFreeDeliveryAvailable() : false);
        restaurant.setFreeDeliveryAbove(request.getFreeDeliveryAbove());
        restaurant.setIsPureVeg(request.getIsPureVeg() != null ? request.getIsPureVeg() : false);
        restaurant.setLicenseNumber(request.getLicenseNumber());
        restaurant.setFssaiNumber(request.getFssaiNumber());

        if (request.getFeatures() != null) restaurant.setFeatures(request.getFeatures());

        // Address
        if (request.getAddress() != null) {
            Address address = new Address();
            address.setAddressLine1(request.getAddress().getAddressLine1());
            address.setAddressLine2(request.getAddress().getAddressLine2());
            address.setCity(request.getAddress().getCity());
            address.setState(request.getAddress().getState());
            address.setPincode(request.getAddress().getPincode());
            address.setLandmark(request.getAddress().getLandmark());
            address.setLatitude(request.getAddress().getLatitude());
            address.setLongitude(request.getAddress().getLongitude());
            restaurant.setAddress(address);
        }

        // Cuisines
        if (request.getCuisineIds() != null && !request.getCuisineIds().isEmpty()) {
            Set<Cuisine> cuisines = request.getCuisineIds().stream()
                    .map(id -> cuisineRepository.findById(id)
                            .orElseThrow(() -> new ResourceNotFoundException("Cuisine not found: " + id)))
                    .collect(Collectors.toSet());
            restaurant.setCuisines(cuisines);
        }

        restaurant = restaurantRepository.save(restaurant);
        invalidateRestaurantCaches();

        return mapToResponse(restaurant);
    }

    @Override
    public RestaurantResponse updateRestaurant(Long id, RestaurantRequest request) {
        Restaurant restaurant = restaurantRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found"));

        if (!restaurant.getOwner().getId().equals(securityUtils.getCurrentUserId())) {
            throw new UnauthorizedException("Not your restaurant");
        }

        if (request.getName() != null) restaurant.setName(request.getName());
        if (request.getDescription() != null) restaurant.setDescription(request.getDescription());
        if (request.getImageUrl() != null) restaurant.setImageUrl(request.getImageUrl());
        if (request.getOpeningTime() != null) restaurant.setOpeningTime(request.getOpeningTime());
        if (request.getClosingTime() != null) restaurant.setClosingTime(request.getClosingTime());
        if (request.getMinimumOrderAmount() != null) restaurant.setMinimumOrderAmount(request.getMinimumOrderAmount());
        if (request.getDeliveryFee() != null) restaurant.setDeliveryFee(request.getDeliveryFee());
        if (request.getIsPureVeg() != null) restaurant.setIsPureVeg(request.getIsPureVeg());
        if (request.getFeatures() != null) restaurant.setFeatures(request.getFeatures());

        // Update address
        if (request.getAddress() != null) {
            Address address = restaurant.getAddress();
            if (address == null) address = new Address();
            address.setAddressLine1(request.getAddress().getAddressLine1());
            address.setAddressLine2(request.getAddress().getAddressLine2());
            address.setCity(request.getAddress().getCity());
            address.setState(request.getAddress().getState());
            address.setPincode(request.getAddress().getPincode());
            address.setLatitude(request.getAddress().getLatitude());
            address.setLongitude(request.getAddress().getLongitude());
            restaurant.setAddress(address);
        }

        restaurant = restaurantRepository.save(restaurant);

        cacheService.delete(CacheKeyGenerator.restaurant(id));
        invalidateRestaurantCaches();

        return mapToResponse(restaurant);
    }

    @Override
    public void deleteRestaurant(Long id) {
        Restaurant restaurant = restaurantRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found"));

        if (!restaurant.getOwner().getId().equals(securityUtils.getCurrentUserId())) {
            throw new UnauthorizedException("Not your restaurant");
        }

        restaurant.setIsActive(false);
        restaurantRepository.save(restaurant);

        cacheService.delete(CacheKeyGenerator.restaurant(id));
        invalidateRestaurantCaches();
    }

    @Override
    public void toggleRestaurantStatus(Long id, Boolean isOpen) {
        Restaurant restaurant = restaurantRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found"));

        if (!restaurant.getOwner().getId().equals(securityUtils.getCurrentUserId())) {
            throw new UnauthorizedException("Not your restaurant");
        }

        restaurant.setIsOpen(isOpen);
        restaurantRepository.save(restaurant);

        cacheService.delete(CacheKeyGenerator.restaurant(id));
        invalidateRestaurantCaches();
    }

    @Override
    public void updateRestaurantRating(Long restaurantId) {
        cacheService.delete(CacheKeyGenerator.restaurant(restaurantId));
        invalidateRestaurantCaches();
    }

    private void invalidateRestaurantCaches() {
        cacheService.deletePattern("restaurant");
    }

    // ==================== MAPPERS ====================

    /**
     * Safe mapper - handles lazy loading gracefully
     * Use this when entity might have unloaded lazy fields
     */
    private RestaurantResponse mapToResponseSafe(Restaurant restaurant) {
        AddressResponse addressResponse = null;
        Set<String> cuisineNames = new HashSet<>();

        try {
            if (restaurant.getAddress() != null) {
                addressResponse = addressMapper.toResponse(restaurant.getAddress());
            }
        } catch (Exception e) {
            log.debug("Could not load address for restaurant: {}", restaurant.getId());
        }

        try {
            if (restaurant.getCuisines() != null) {
                cuisineNames = restaurant.getCuisines().stream()
                        .map(Cuisine::getName)
                        .collect(Collectors.toSet());
            }
        } catch (Exception e) {
            log.debug("Could not load cuisines for restaurant: {}", restaurant.getId());
        }

        return RestaurantResponse.builder()
                .id(restaurant.getId())
                .name(restaurant.getName())
                .description(restaurant.getDescription())
                .address(addressResponse)
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
                .build();
    }

    /**
     * Full mapper - requires all lazy fields to be loaded via JOIN FETCH
     */
    private RestaurantResponse mapToResponse(Restaurant restaurant) {
        AddressResponse addressResponse = null;
        Set<String> cuisineNames = new HashSet<>();

        if (restaurant.getAddress() != null) {
            addressResponse = addressMapper.toResponse(restaurant.getAddress());
        }

        if (restaurant.getCuisines() != null) {
            cuisineNames = restaurant.getCuisines().stream()
                    .map(Cuisine::getName)
                    .collect(Collectors.toSet());
        }

        return RestaurantResponse.builder()
                .id(restaurant.getId())
                .name(restaurant.getName())
                .description(restaurant.getDescription())
                .address(addressResponse)
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
                .build();
    }
}