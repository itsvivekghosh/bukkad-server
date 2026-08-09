package com.bhukkad.serviceImpl;

import com.bhukkad.cache.CacheKeyGenerator;
import com.bhukkad.cache.RedisCacheService;
import com.bhukkad.dto.request.RestaurantRequest;
import com.bhukkad.dto.response.AddressResponse;
import com.bhukkad.dto.response.RestaurantResponse;
import com.bhukkad.entity.Address;
import com.bhukkad.entity.Cuisine;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.entity.RestaurantOwner;
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
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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

    @Value("${cache.ttl.restaurant:1800}")
    private long restaurantTtl;

    @Value("${cache.ttl.restaurant-list:600}")
    private long restaurantListTtl;

    @Value("${cache.ttl.search:300}")
    private long searchTtl;

    @Override
    public RestaurantResponse getRestaurantById(Long id) {
        String cacheKey = CacheKeyGenerator.restaurant(id);

        // Try cache first
        Optional<RestaurantResponse> cached = cacheService.get(cacheKey, RestaurantResponse.class);
        if (cached.isPresent()) {
            log.debug("CACHE_HIT restaurant id={}", id);
            return cached.get();
        }

        // Cache miss - get from DB
        log.debug("CACHE_MISS restaurant id={}", id);
        Restaurant restaurant = restaurantRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found"));

        RestaurantResponse response = mapToRestaurantResponse(restaurant);

        // Store in cache
        cacheService.set(cacheKey, response, restaurantTtl);

        return response;
    }

    @Override
    public List<RestaurantResponse> getAllActiveRestaurants() {
        String cacheKey = CacheKeyGenerator.restaurantList();

        // Try cache first
        Optional<List<RestaurantResponse>> cached = cacheService.getList(cacheKey, RestaurantResponse.class);
        if (cached.isPresent()) {
            log.debug("CACHE_HIT restaurant-list");
            return cached.get();
        }

        // Cache miss
        log.debug("CACHE_MISS restaurant-list");
        List<RestaurantResponse> restaurants = restaurantRepository.findByIsActiveTrue().stream()
                .map(this::mapToRestaurantResponse)
                .collect(Collectors.toList());

        cacheService.set(cacheKey, restaurants, restaurantListTtl);

        return restaurants;
    }

    @Override
    public List<RestaurantResponse> searchRestaurants(String keyword) {
        String cacheKey = CacheKeyGenerator.restaurantSearch(keyword);

        Optional<List<RestaurantResponse>> cached = cacheService.getList(cacheKey, RestaurantResponse.class);
        if (cached.isPresent()) {
            log.debug("CACHE_HIT restaurant-search keyword={}", keyword);
            return cached.get();
        }

        log.debug("CACHE_MISS restaurant-search keyword={}", keyword);
        List<RestaurantResponse> results = restaurantRepository.searchByName(keyword).stream()
                .map(this::mapToRestaurantResponse)
                .collect(Collectors.toList());

        cacheService.set(cacheKey, results, searchTtl);

        return results;
    }

    @Override
    public List<RestaurantResponse> filterRestaurants(Long cuisineId, Boolean isPureVeg) {
        String cacheKey = CacheKeyGenerator.restaurantFilter(cuisineId, isPureVeg);

        Optional<List<RestaurantResponse>> cached = cacheService.getList(cacheKey, RestaurantResponse.class);
        if (cached.isPresent()) {
            log.debug("CACHE_HIT restaurant-filter cuisineId={} isPureVeg={}", cuisineId, isPureVeg);
            return cached.get();
        }

        log.debug("CACHE_MISS restaurant-filter");
        List<RestaurantResponse> results = restaurantRepository.findByFilters(cuisineId, isPureVeg).stream()
                .map(this::mapToRestaurantResponse)
                .collect(Collectors.toList());

        cacheService.set(cacheKey, results, searchTtl);

        return results;
    }

    @Override
    @Transactional
    public RestaurantResponse createRestaurant(RestaurantRequest request) {
        Long ownerId = securityUtils.getCurrentUserId();
        RestaurantOwner owner = restaurantOwnerRepository.findById(ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Owner not found"));

        Restaurant restaurant = new Restaurant();
        restaurant.setName(request.getName());
        restaurant.setDescription(request.getDescription());
        restaurant.setOwner(owner);
        restaurant.setOpeningTime(request.getOpeningTime());
        restaurant.setClosingTime(request.getClosingTime());
        restaurant.setMinimumOrderAmount(request.getMinimumOrderAmount());
        restaurant.setDeliveryFee(request.getDeliveryFee());
        restaurant.setIsPureVeg(request.getIsPureVeg());

        if (request.getAddress() != null) {
            Address address = new Address();
            address.setAddressLine1(request.getAddress().getAddressLine1());
            address.setCity(request.getAddress().getCity());
            address.setState(request.getAddress().getState());
            address.setPincode(request.getAddress().getPincode());
            address.setLatitude(request.getAddress().getLatitude());
            address.setLongitude(request.getAddress().getLongitude());
            restaurant.setAddress(address);
        }

        restaurant = restaurantRepository.save(restaurant);
        RestaurantResponse response = mapToRestaurantResponse(restaurant);

        // Invalidate list caches
        invalidateRestaurantCaches();

        return response;
    }

    @Override
    @Transactional
    public RestaurantResponse updateRestaurant(Long id, RestaurantRequest request) {
        Restaurant restaurant = restaurantRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found"));

        if (!restaurant.getOwner().getId().equals(securityUtils.getCurrentUserId())) {
            throw new UnauthorizedException("Not your restaurant");
        }

        restaurant.setName(request.getName());
        restaurant.setDescription(request.getDescription());
        restaurant.setMinimumOrderAmount(request.getMinimumOrderAmount());
        restaurant.setDeliveryFee(request.getDeliveryFee());

        restaurant = restaurantRepository.save(restaurant);
        RestaurantResponse response = mapToRestaurantResponse(restaurant);

        // Invalidate specific + list caches
        cacheService.delete(CacheKeyGenerator.restaurant(id));
        invalidateRestaurantCaches();

        return response;
    }

    @Override
    @Transactional
    public void deleteRestaurant(Long id) {
        Restaurant restaurant = restaurantRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found"));

        if (!restaurant.getOwner().getId().equals(securityUtils.getCurrentUserId())) {
            throw new UnauthorizedException("Not your restaurant");
        }

        restaurant.setIsActive(false);
        restaurantRepository.save(restaurant);

        // Invalidate all related caches
        cacheService.delete(CacheKeyGenerator.restaurant(id));
        invalidateRestaurantCaches();
    }

    @Override
    @Transactional
    public void toggleRestaurantStatus(Long id, Boolean isOpen) {
        Restaurant restaurant = restaurantRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found"));

        restaurant.setIsOpen(isOpen);
        restaurantRepository.save(restaurant);

        // Invalidate
        cacheService.delete(CacheKeyGenerator.restaurant(id));
        invalidateRestaurantCaches();
    }

    @Override
    public List<RestaurantResponse> getRestaurantsByOwner(Long ownerId) {
        String cacheKey = CacheKeyGenerator.restaurantsByOwner(ownerId);

        Optional<List<RestaurantResponse>> cached = cacheService.getList(cacheKey, RestaurantResponse.class);
        if (cached.isPresent()) return cached.get();

        List<RestaurantResponse> results = restaurantRepository.findByOwnerId(ownerId).stream()
                .map(this::mapToRestaurantResponse)
                .collect(Collectors.toList());

        cacheService.set(cacheKey, results, restaurantListTtl);
        return results;
    }

    @Override
    public List<RestaurantResponse> getMyRestaurants() {
        return getRestaurantsByOwner(securityUtils.getCurrentUserId());
    }

    @Override
    public void updateRestaurantRating(Long restaurantId) {
        cacheService.delete(CacheKeyGenerator.restaurant(restaurantId));
        invalidateRestaurantCaches();
    }

    private void invalidateRestaurantCaches() {
        cacheService.deletePattern(CacheKeyGenerator.restaurantPattern());
        log.debug("CACHE_INVALIDATED restaurant-related caches");
    }

    private RestaurantResponse mapToRestaurantResponse(Restaurant restaurant) {
        return RestaurantResponse.builder()
                .id(restaurant.getId())
                .name(restaurant.getName())
                .description(restaurant.getDescription())
                .address(restaurant.getAddress() != null ? mapToAddressResponse(restaurant.getAddress()) : null)
                .cuisines(restaurant.getCuisines().stream().map(Cuisine::getName).collect(Collectors.toSet()))
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

    private AddressResponse mapToAddressResponse(Address address) {
        return AddressResponse.builder()
                .id(address.getId())
                .addressLine1(address.getAddressLine1())
                .city(address.getCity())
                .state(address.getState())
                .pincode(address.getPincode())
                .latitude(address.getLatitude())
                .longitude(address.getLongitude())
                .build();
    }
}