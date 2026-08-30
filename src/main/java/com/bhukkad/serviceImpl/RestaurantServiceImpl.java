package com.bhukkad.serviceImpl;

import com.bhukkad.cache.CacheKeyGenerator;
import com.bhukkad.cache.RedisCacheService;
import com.bhukkad.datasource.UseReadReplica;
import com.bhukkad.geo.RestaurantGeoIndexService;
import com.bhukkad.dto.request.RestaurantRequest;
import com.bhukkad.mapper.AddressMapper;
import com.bhukkad.dto.response.AddressResponse;
import com.bhukkad.dto.response.RestaurantOnboardingStatusResponse;
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
import com.bhukkad.search.AutocompleteService;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.service.RestaurantService;
import com.bhukkad.util.Constants;
import com.bhukkad.util.DistanceCalculator;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    private final RestaurantGeoIndexService restaurantGeoIndexService;
    private final AddressMapper addressMapper;
    private final AutocompleteService autocompleteService;

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
        return cacheService.getOrCompute(cacheKey, RestaurantResponse.class, restaurantTtl, () -> {
            Restaurant restaurant = restaurantRepository.findByIdWithDetails(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found"));
            return mapToResponse(restaurant);
        });
    }

    @Override
    @UseReadReplica
    public List<RestaurantResponse> getAllActiveRestaurants() {
        return getAllActiveRestaurants(null, null, null, null);
    }

    @Override
    @UseReadReplica
    public List<RestaurantResponse> getAllActiveRestaurants(Long tenantId) {
        return getAllActiveRestaurants(tenantId, null, null, null);
    }

    @Override
    @UseReadReplica
    public List<RestaurantResponse> getActiveRestaurantsInRadius(Double latitude, Double longitude, Double radiusKm) {
        return getAllActiveRestaurants(null, latitude, longitude, radiusKm);
    }

    private static final int RESTAURANT_LIST_MAX = 200;

    @Override
    @UseReadReplica
    public List<RestaurantResponse> getAllActiveRestaurants(Long tenantId, Double latitude, Double longitude, Double radiusKm) {
        // When a location + radius is provided, delegate to the geo-optimized nearby
        // path (Redis GEO or SQL Haversine) instead of fetching the full global list.
        if (latitude != null && longitude != null && radiusKm != null) {
            return findNearbyRestaurants(latitude, longitude, radiusKm, 100);
        }

        String cacheKey = CacheKeyGenerator.restaurantList(tenantId);
        return cacheService.getListOrCompute(cacheKey, RestaurantResponse.class, restaurantListTtl, () -> {
            List<RestaurantResponse> restaurants = restaurantRepository.findAllActiveWithDetails()
                    .stream()
                    .filter(r -> !Restaurant.OnboardingStatus.REJECTED.equals(r.getOnboardingStatus())
                            && !Restaurant.OnboardingStatus.SUSPENDED.equals(r.getOnboardingStatus()))
                    .filter(r -> tenantId == null || tenantId.equals(r.getTenantId()))
                    .limit(RESTAURANT_LIST_MAX)
                    .map(this::mapToResponse)
                    .collect(Collectors.toList());
            if (restaurants.size() >= RESTAURANT_LIST_MAX) {
                log.warn("RESTAURANT_LIST_CAPPED | tenantId={} | size={} | max={}", tenantId, restaurants.size(), RESTAURANT_LIST_MAX);
            }
            return restaurants;
        });
    }

    @Override
    @UseReadReplica
    public List<RestaurantResponse> getMyRestaurants() {
        Long ownerId = securityUtils.getCurrentUserId();
        String cacheKey = CacheKeyGenerator.restaurantsByOwner(ownerId);
        return cacheService.getListOrCompute(cacheKey, RestaurantResponse.class, restaurantListTtl, () ->
                restaurantRepository.findByOwnerIdWithDetails(ownerId)
                        .stream()
                        .map(this::mapToResponse)
                        .collect(Collectors.toList()));
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
        if (keyword == null || keyword.trim().length() < 2) {
            return List.of();
        }
        String trimmed = keyword.trim();
        String cacheKey = CacheKeyGenerator.restaurantSearch(trimmed);
        return cacheService.getListOrCompute(cacheKey, RestaurantResponse.class, searchTtl, () -> {
            List<Restaurant> results;
            try {
                results = restaurantRepository.fullTextSearchByName(trimmed);
                if (results.isEmpty() && trimmed.length() >= 3) {
                    // Fallback only for >=3 chars to avoid full scan on single char
                    results = restaurantRepository.searchByNameWithDetails(trimmed);
                }
            } catch (Exception ex) {
                log.debug("RESTAURANT_FULLTEXT_FALLBACK | keyword={}", trimmed);
                results = trimmed.length() >= 3 ? restaurantRepository.searchByNameWithDetails(trimmed) : List.of();
            }
            // Batch-fetch lazy associations for all results in ONE query instead of
            // one findByIdWithDetails per restaurant (N+1).
            List<Long> ids = results.stream().map(Restaurant::getId).toList();
            if (ids.isEmpty()) {
                return List.of();
            }
            Map<Long, Restaurant> byId = restaurantRepository.findAllByIdsWithDetails(ids).stream()
                    .collect(Collectors.toMap(Restaurant::getId, r -> r));
            return ids.stream()
                    .map(byId::get)
                    .filter(Objects::nonNull)
                    .map(this::mapToResponse)
                    .collect(Collectors.toList());
        });
    }

    @Override
    @UseReadReplica
    public List<RestaurantResponse> findNearbyRestaurants(
            double latitude, double longitude, double radiusKm, int limit) {
        return findNearbyRestaurantsInternal(latitude, longitude, radiusKm, limit,
                (a, b) -> 0); // preserve Redis GEO / SQL query ordering (by distance)
    }

    @Override
    @UseReadReplica
    public List<RestaurantResponse> findTopRatedNearbyRestaurants(
            double latitude, double longitude, double radiusKm, int limit) {
        return findNearbyRestaurantsInternal(latitude, longitude, radiusKm, limit,
                (a, b) -> {
                    double da = a.getAverageRating() != null ? a.getAverageRating() : 0.0;
                    double db = b.getAverageRating() != null ? b.getAverageRating() : 0.0;
                    int cmp = Double.compare(db, da); // descending
                    if (cmp != 0) return cmp;
                    int ra = a.getTotalReviews() != null ? a.getTotalReviews() : 0;
                    int rb = b.getTotalReviews() != null ? b.getTotalReviews() : 0;
                    return Integer.compare(rb, ra); // descending reviews as tiebreaker
                });
    }

    /**
     * Shared proximity-finding logic with a pluggable sort comparator.
     * The geo index (Redis GEO) is checked first; if empty, the SQL Haversine
     * fallback with bounding-box pre-filter runs. Results are sorted by the
     * provided comparator.
     */
    private List<RestaurantResponse> findNearbyRestaurantsInternal(
            double latitude, double longitude, double radiusKm, int limit,
            java.util.Comparator<Restaurant> sortComparator) {
        String cacheKey = CacheKeyGenerator.restaurantNearby(latitude, longitude, radiusKm);
        int safeLimit = Math.min(Math.max(limit, 1), 50);
        double safeRadius = Math.min(Math.max(radiusKm, 0.5), 50.0);
        return cacheService.getListOrCompute(cacheKey, RestaurantResponse.class, searchTtl, () -> {
            // Add a small epsilon to the radius for both the Redis GEO path and
            // the SQL fallback to make the boundary inclusive across implementations.
            // Redis GEO and MySQL Haversine may round differently at the exact
            // boundary due to floating-point precision.
            double epsilonKm = Constants.PROXIMITY_RADIUS_EPSILON_KM;
            List<Long> ids = restaurantGeoIndexService.findNearbyRestaurantIds(
                    latitude, longitude, safeRadius + epsilonKm, safeLimit);
            if (ids.isEmpty()) {
                // SQL Haversine fallback with bounding-box pre-filter for
                // large datasets — the BETWEEN clauses on latitude/longitude
                // leverage idx_address_lat_lon, avoiding a full table scan.
                double[] deltas = DistanceCalculator.boundingBoxDeltas(safeRadius + epsilonKm);
                ids = restaurantRepository.findNearbyRestaurantIds(
                        latitude, longitude, deltas[0], deltas[1], safeRadius, epsilonKm, safeLimit);
            }
            if (ids.isEmpty()) {
                return List.of();
            }
            // Batch-fetch all nearby restaurants in ONE query instead of one
            // findByIdWithDetails per id (N+1).
            Map<Long, Restaurant> byId = restaurantRepository.findAllByIdsWithDetails(ids).stream()
                    .collect(Collectors.toMap(Restaurant::getId, r -> r));
            List<Restaurant> sorted = ids.stream()
                    .map(byId::get)
                    .filter(Objects::nonNull)
                    .sorted(sortComparator)
                    .collect(Collectors.toList());
            return sorted.stream()
                    .map(r -> mapToResponse(r, latitude, longitude))
                    .collect(Collectors.toList());
        });
    }

    @Override
    @UseReadReplica
    public List<RestaurantResponse> filterRestaurants(Long cuisineId, Boolean isPureVeg) {
        String cacheKey = CacheKeyGenerator.restaurantFilter(cuisineId, isPureVeg);
        return cacheService.getListOrCompute(cacheKey, RestaurantResponse.class, searchTtl, () ->
                restaurantRepository.findByFilters(cuisineId, isPureVeg)
                        .stream()
                        .map(this::mapToResponse)
                        .collect(Collectors.toList()));
    }

    @Override
    public java.util.List<RestaurantResponse> getRestaurantsByIds(java.util.List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        // Use fetch-join batch to avoid N+1 lazy loads for address/cuisines (was findAllById → 2N queries)
        return restaurantRepository.findAllByIdsWithDetails(ids).stream()
                .map(this::mapToResponse)
                .collect(java.util.stream.Collectors.toList());
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
        restaurant.setTenantId(request.getTenantId());

        if (request.getFeatures() != null) restaurant.setFeatures(request.getFeatures());

        if (request.getVirtualBrandName() != null) restaurant.setVirtualBrandName(request.getVirtualBrandName());

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

        // Cuisines (batch fetch: one IN query instead of one findById per id)
        if (request.getCuisineIds() != null && !request.getCuisineIds().isEmpty()) {
            restaurant.setCuisines(resolveCuisines(request.getCuisineIds()));
        }

        restaurant = restaurantRepository.save(restaurant);
        restaurantGeoIndexService.indexRestaurant(restaurant);
        invalidateRestaurantCaches();
        autocompleteService.indexRestaurant(restaurant.getId(), restaurant.getName());

        return mapToResponse(restaurant);
    }

    @Override
    public RestaurantResponse createOnboardingApplication(RestaurantRequest request) {
        Long ownerId = securityUtils.getCurrentUserId();
        RestaurantOwner owner = restaurantOwnerRepository.findById(ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Owner not found"));

        if (!StringUtils.hasText(request.getLicenseNumber()) || !StringUtils.hasText(request.getFssaiNumber())) {
            throw new BusinessException("Business license and FSSAI number are required for onboarding");
        }

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
        restaurant.setTenantId(request.getTenantId());
        restaurant.setOnboardingStatus(Restaurant.OnboardingStatus.PENDING_VERIFICATION);
        restaurant.setOnboardingRejectionReason(null);

        if (request.getFeatures() != null) restaurant.setFeatures(request.getFeatures());
        if (request.getVirtualBrandName() != null) restaurant.setVirtualBrandName(request.getVirtualBrandName());

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

        if (request.getCuisineIds() != null && !request.getCuisineIds().isEmpty()) {
            restaurant.setCuisines(resolveCuisines(request.getCuisineIds()));
        }

        restaurant = restaurantRepository.save(restaurant);
        invalidateRestaurantCaches();
        return mapToResponse(restaurant);
    }

    @Override
    @UseReadReplica
    public RestaurantOnboardingStatusResponse getOnboardingStatus() {
        Long ownerId = securityUtils.getCurrentUserId();
        List<RestaurantOnboardingStatusResponse.RestaurantOnboardingItem> items =
                restaurantRepository.findByOwnerIdWithDetails(ownerId)
                        .stream()
                        .map(r -> RestaurantOnboardingStatusResponse.RestaurantOnboardingItem.builder()
                                .restaurantId(r.getId())
                                .name(r.getName())
                                .onboardingStatus(r.getOnboardingStatus().name())
                                .rejectionReason(r.getOnboardingRejectionReason())
                                .isActive(r.getIsActive())
                                .build())
                        .toList();
        return RestaurantOnboardingStatusResponse.builder().restaurants(items).build();
    }

    @Override
    public void reviewOnboarding(Long restaurantId, boolean approved, String reason) {
        Restaurant restaurant = restaurantRepository.findByIdWithDetails(restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found"));
        if (approved) {
            restaurant.setOnboardingStatus(Restaurant.OnboardingStatus.APPROVED);
            restaurant.setOnboardingRejectionReason(null);
            restaurant.setIsActive(true);
        } else {
            if (!StringUtils.hasText(reason)) {
                throw new BusinessException("Rejection reason is required");
            }
            restaurant.setOnboardingStatus(Restaurant.OnboardingStatus.REJECTED);
            restaurant.setOnboardingRejectionReason(reason);
            restaurant.setIsActive(false);
            restaurant.setIsOpen(false);
        }
        restaurantRepository.save(restaurant);
        cacheService.delete(CacheKeyGenerator.restaurant(restaurantId));
        invalidateRestaurantCaches();
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

        if (request.getVirtualBrandName() != null) restaurant.setVirtualBrandName(request.getVirtualBrandName());

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
        restaurantGeoIndexService.indexRestaurant(restaurant);

        cacheService.delete(CacheKeyGenerator.restaurant(id));
        invalidateRestaurantCaches();
        autocompleteService.indexRestaurant(restaurant.getId(), restaurant.getName());

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
     * Convenience overload that delegates to the main mapper and then
     * populates {@code distanceKm} from the given reference coordinates.
     * Called only from proximity paths — the reference coords are always valid doubles.
     */
    private RestaurantResponse mapToResponse(Restaurant restaurant, double latitude, double longitude) {
        RestaurantResponse response = mapToResponse(restaurant);
        if (restaurant.getAddress() != null
                && restaurant.getAddress().getLatitude() != null
                && restaurant.getAddress().getLongitude() != null) {
            response.setDistanceKm(DistanceCalculator.calculateDistance(
                    latitude, longitude,
                    restaurant.getAddress().getLatitude(),
                    restaurant.getAddress().getLongitude()));
        }
        return response;
    }

    /**
     * Maps a Restaurant entity to its response DTO.
     *
     * <p>Address and cuisines are lazy associations. When the entity was loaded
     * with a JOIN FETCH query they are already initialized and the try/catch is a
     * no-op; when loaded without the fetch (e.g. nearby-search paths) the guard
     * degrades gracefully to {@code null} / an empty set instead of throwing a
     * LazyInitializationException. This single method replaces the previous
     * duplicated {@code mapToResponseSafe}/{@code mapToResponse} pair.</p>
     */
    private RestaurantResponse mapToResponse(Restaurant restaurant) {
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
                .features(restaurant.getFeatures() == null ? Set.of() : new java.util.HashSet<>(restaurant.getFeatures()))
                .virtualBrandName(restaurant.getVirtualBrandName())
                .onboardingStatus(restaurant.getOnboardingStatus() != null ? restaurant.getOnboardingStatus().name() : null)
                .tenantId(restaurant.getTenantId())
                .build();
    }

    /**
     * Resolves cuisine ids to entities in a single batch query, throwing on any
     * id that does not exist. Replaces the previous N+1 loop of per-id lookups.
     */
    private Set<Cuisine> resolveCuisines(Set<Long> cuisineIds) {
        List<Long> distinctIds = cuisineIds.stream().distinct().toList();
        Map<Long, Cuisine> byId = cuisineRepository.findAllById(distinctIds).stream()
                .collect(Collectors.toMap(Cuisine::getId, c -> c));
        for (Long id : distinctIds) {
            if (!byId.containsKey(id)) {
                throw new ResourceNotFoundException("Cuisine not found: " + id);
            }
        }
        return new HashSet<>(byId.values());
    }
}
