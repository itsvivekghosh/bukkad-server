package com.bhukkad.serviceImpl;

import com.bhukkad.cache.CacheKeyGenerator;
import com.bhukkad.cache.RedisCacheService;
import com.bhukkad.dto.request.AddressRequest;
import com.bhukkad.dto.request.RestaurantRequest;
import com.bhukkad.dto.response.AddressResponse;
import com.bhukkad.dto.response.RestaurantResponse;
import com.bhukkad.entity.Address;
import com.bhukkad.entity.Cuisine;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.entity.RestaurantOwner;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.exception.UnauthorizedException;
import com.bhukkad.mapper.AddressMapper;
import com.bhukkad.repository.CuisineRepository;
import com.bhukkad.repository.RestaurantOwnerRepository;
import com.bhukkad.repository.RestaurantRepository;
import com.bhukkad.search.AutocompleteService;
import com.bhukkad.security.SecurityUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RestaurantServiceImplTest {

    @Mock
    private RestaurantRepository restaurantRepository;
    @Mock
    private RestaurantOwnerRepository restaurantOwnerRepository;
    @Mock
    private CuisineRepository cuisineRepository;
    @Mock
    private SecurityUtils securityUtils;

    @Mock
    private AutocompleteService autocompleteService;
    @Mock
    private RedisCacheService cacheService;
    @Mock
    private com.bhukkad.geo.RestaurantGeoIndexService restaurantGeoIndexService;
    @Mock
    private AddressMapper addressMapper;

    @InjectMocks
    private RestaurantServiceImpl restaurantService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(restaurantService, "restaurantTtl", 1800L);
        ReflectionTestUtils.setField(restaurantService, "restaurantListTtl", 600L);
        ReflectionTestUtils.setField(restaurantService, "searchTtl", 300L);

        lenient().when(addressMapper.toResponse(any(Address.class))).thenAnswer(invocation -> {
            Address address = invocation.getArgument(0);
            if (address == null) {
                return null;
            }
            return AddressResponse.builder()
                    .id(address.getId())
                    .addressLine1(address.getAddressLine1())
                    .addressLine2(address.getAddressLine2())
                    .city(address.getCity())
                    .state(address.getState())
                    .pincode(address.getPincode())
                    .landmark(address.getLandmark())
                    .latitude(address.getLatitude())
                    .longitude(address.getLongitude())
                    .build();
        });

        lenient().when(cacheService.getListOrCompute(anyString(), any(), anyLong(), any()))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(0);
                    Class<?> type = invocation.getArgument(1);
                    long ttl = invocation.getArgument(2);
                    Supplier<?> supplier = invocation.getArgument(3);
                    Optional<?> cached = cacheService.getList(key, type);
                    if (cached.isPresent()) {
                        return cached.get();
                    }
                    Object value = supplier.get();
                    cacheService.set(key, value, ttl);
                    return value;
                });

        lenient().when(cacheService.getOrCompute(anyString(), any(), anyLong(), any()))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(0);
                    Class<?> type = invocation.getArgument(1);
                    long ttl = invocation.getArgument(2);
                    Supplier<?> supplier = invocation.getArgument(3);
                    Optional<?> cached = cacheService.get(key, type);
                    if (cached.isPresent()) {
                        return cached.get();
                    }
                    Object value = supplier.get();
                    cacheService.set(key, value, ttl);
                    return value;
                });
    }

    // ==================== getRestaurantById ====================

    @Test
    void getRestaurantById_cacheHit_returnsCachedAndSkipsRepository() {
        RestaurantResponse cached = RestaurantResponse.builder().id(1L).name("Cached").build();
        when(cacheService.get(CacheKeyGenerator.restaurant(1L), RestaurantResponse.class))
                .thenReturn(Optional.of(cached));

        RestaurantResponse result = restaurantService.getRestaurantById(1L);

        assertSame(cached, result);
        verify(restaurantRepository, never()).findByIdWithDetails(any());
        verify(cacheService, never()).set(anyString(), any(), anyLong());
    }

    @Test
    void getRestaurantById_cacheMiss_loadsMapsCachesAndReturns() {
        when(cacheService.get(CacheKeyGenerator.restaurant(1L), RestaurantResponse.class))
                .thenReturn(Optional.empty());
        Restaurant restaurant = fullRestaurant(1L, "Spice Hub");
        when(restaurantRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(restaurant));

        RestaurantResponse result = restaurantService.getRestaurantById(1L);

        assertEquals(1L, result.getId());
        assertEquals("Spice Hub", result.getName());
        assertEquals("Best biryani", result.getDescription());
        assertNotNull(result.getAddress());
        assertEquals("12 MG Road", result.getAddress().getAddressLine1());
        assertEquals("Near park", result.getAddress().getLandmark());
        assertTrue(result.getCuisines().contains("Indian"));
        assertEquals(LocalTime.of(10, 0), result.getOpeningTime());
        assertEquals(Boolean.TRUE, result.getIsOpen());
        verify(cacheService).set(eq(CacheKeyGenerator.restaurant(1L)), any(RestaurantResponse.class), eq(1800L));
    }

    @Test
    void getRestaurantById_cacheMissWithoutAddressOrCuisines_mapsNulls() {
        when(cacheService.get(anyString(), eq(RestaurantResponse.class))).thenReturn(Optional.empty());
        Restaurant restaurant = new Restaurant();
        restaurant.setId(2L);
        restaurant.setName("Bare");
        restaurant.setAddress(null);
        restaurant.setCuisines(null);
        when(restaurantRepository.findByIdWithDetails(2L)).thenReturn(Optional.of(restaurant));

        RestaurantResponse result = restaurantService.getRestaurantById(2L);

        assertEquals("Bare", result.getName());
        assertNull(result.getAddress());
        assertTrue(result.getCuisines().isEmpty());
    }

    @Test
    void getRestaurantById_notFound_throwsResourceNotFound() {
        when(cacheService.get(anyString(), eq(RestaurantResponse.class))).thenReturn(Optional.empty());
        when(restaurantRepository.findByIdWithDetails(99L)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> restaurantService.getRestaurantById(99L));
        assertEquals("Restaurant not found", ex.getMessage());
    }

    // ==================== getAllActiveRestaurants ====================

    @Test
    void getAllActiveRestaurants_cacheHit_returnsCached() {
        List<RestaurantResponse> cached = List.of(RestaurantResponse.builder().id(1L).name("A").build());
        when(cacheService.getList(CacheKeyGenerator.restaurantList(), RestaurantResponse.class))
                .thenReturn(Optional.of(cached));

        List<RestaurantResponse> result = restaurantService.getAllActiveRestaurants();

        assertSame(cached, result);
        verify(restaurantRepository, never()).findAllActiveWithDetails();
    }

    @Test
    void getAllActiveRestaurants_cacheMiss_loadsCachesAndReturns() {
        when(cacheService.getList(CacheKeyGenerator.restaurantList(), RestaurantResponse.class))
                .thenReturn(Optional.empty());
        when(restaurantRepository.findAllActiveWithDetails()).thenReturn(List.of(fullRestaurant(1L, "A")));

        List<RestaurantResponse> result = restaurantService.getAllActiveRestaurants();

        assertEquals(1, result.size());
        assertEquals("A", result.get(0).getName());
        verify(cacheService).set(eq(CacheKeyGenerator.restaurantList()), any(), eq(600L));
    }

    // ==================== getMyRestaurants ====================

    @Test
    void getMyRestaurants_cacheHit_returnsCached() {
        when(securityUtils.getCurrentUserId()).thenReturn(7L);
        List<RestaurantResponse> cached = List.of(RestaurantResponse.builder().id(3L).build());
        when(cacheService.getList(CacheKeyGenerator.restaurantsByOwner(7L), RestaurantResponse.class))
                .thenReturn(Optional.of(cached));

        assertSame(cached, restaurantService.getMyRestaurants());
        verify(restaurantRepository, never()).findByOwnerIdWithDetails(any());
    }

    @Test
    void getMyRestaurants_cacheMiss_loadsCachesAndReturns() {
        when(securityUtils.getCurrentUserId()).thenReturn(7L);
        when(cacheService.getList(CacheKeyGenerator.restaurantsByOwner(7L), RestaurantResponse.class))
                .thenReturn(Optional.empty());
        when(restaurantRepository.findByOwnerIdWithDetails(7L)).thenReturn(List.of(fullRestaurant(4L, "Mine")));

        List<RestaurantResponse> result = restaurantService.getMyRestaurants();

        assertEquals(1, result.size());
        assertEquals("Mine", result.get(0).getName());
        verify(cacheService).set(eq(CacheKeyGenerator.restaurantsByOwner(7L)), any(), eq(600L));
    }

    // ==================== getRestaurantsByOwner ====================

    @Test
    void getRestaurantsByOwner_mapsWithoutCache() {
        when(restaurantRepository.findByOwnerIdWithDetails(8L)).thenReturn(List.of(fullRestaurant(5L, "Owned")));

        List<RestaurantResponse> result = restaurantService.getRestaurantsByOwner(8L);

        assertEquals(1, result.size());
        assertEquals("Owned", result.get(0).getName());
        verifyNoInteractions(cacheService);
    }

    @Test
    void getRestaurantsByOwner_emptyList() {
        when(restaurantRepository.findByOwnerIdWithDetails(8L)).thenReturn(List.of());
        assertTrue(restaurantService.getRestaurantsByOwner(8L).isEmpty());
    }

    // ==================== searchRestaurants ====================

    @Test
    void searchRestaurants_cacheHit_returnsCached() {
        List<RestaurantResponse> cached = List.of(RestaurantResponse.builder().name("Pizza").build());
        when(cacheService.getList(CacheKeyGenerator.restaurantSearch("Pizza"), RestaurantResponse.class))
                .thenReturn(Optional.of(cached));

        assertSame(cached, restaurantService.searchRestaurants("Pizza"));
        verify(restaurantRepository, never()).searchByNameWithDetails(any());
    }

    @Test
    void searchRestaurants_cacheMiss_loadsCachesAndReturns() {
        when(cacheService.getList(CacheKeyGenerator.restaurantSearch("biryani"), RestaurantResponse.class))
                .thenReturn(Optional.empty());
        when(restaurantRepository.searchByNameWithDetails("biryani"))
                .thenReturn(List.of(fullRestaurant(1L, "Biryani House")));
        when(restaurantRepository.findAllByIdsWithDetails(List.of(1L)))
                .thenReturn(List.of(fullRestaurant(1L, "Biryani House")));

        List<RestaurantResponse> result = restaurantService.searchRestaurants("biryani");

        assertEquals("Biryani House", result.get(0).getName());
        verify(cacheService).set(eq(CacheKeyGenerator.restaurantSearch("biryani")), any(), eq(300L));
    }

    // ==================== filterRestaurants ====================

    @Test
    void filterRestaurants_cacheHit_returnsCached() {
        List<RestaurantResponse> cached = List.of(RestaurantResponse.builder().id(1L).build());
        when(cacheService.getList(CacheKeyGenerator.restaurantFilter(3L, true), RestaurantResponse.class))
                .thenReturn(Optional.of(cached));

        assertSame(cached, restaurantService.filterRestaurants(3L, true));
        verify(restaurantRepository, never()).findByFilters(any(), any());
    }

    @Test
    void filterRestaurants_cacheMiss_mapsSafelyAndCaches() {
        when(cacheService.getList(CacheKeyGenerator.restaurantFilter(null, false), RestaurantResponse.class))
                .thenReturn(Optional.empty());
        when(restaurantRepository.findByFilters(null, false)).thenReturn(List.of(fullRestaurant(1L, "Veg")));

        List<RestaurantResponse> result = restaurantService.filterRestaurants(null, false);

        assertEquals(1, result.size());
        assertEquals("Veg", result.get(0).getName());
        assertNotNull(result.get(0).getAddress());
        assertFalse(result.get(0).getCuisines().isEmpty());
        verify(cacheService).set(eq(CacheKeyGenerator.restaurantFilter(null, false)), any(), eq(300L));
    }

    @Test
    void filterRestaurants_nullAddressAndCuisines_safeMapperLeavesEmpty() {
        when(cacheService.getList(anyString(), eq(RestaurantResponse.class))).thenReturn(Optional.empty());
        Restaurant restaurant = new Restaurant();
        restaurant.setId(9L);
        restaurant.setName("Empty");
        restaurant.setAddress(null);
        restaurant.setCuisines(null);
        when(restaurantRepository.findByFilters(1L, true)).thenReturn(List.of(restaurant));

        RestaurantResponse result = restaurantService.filterRestaurants(1L, true).get(0);

        assertNull(result.getAddress());
        assertTrue(result.getCuisines().isEmpty());
    }

    @Test
    void filterRestaurants_lazyAddressFailure_stillReturnsRestaurant() {
        when(cacheService.getList(anyString(), eq(RestaurantResponse.class))).thenReturn(Optional.empty());
        Restaurant restaurant = spy(fullRestaurant(11L, "Lazy Address"));
        doThrow(new RuntimeException("could not initialize proxy")).when(restaurant).getAddress();
        when(restaurantRepository.findByFilters(2L, null)).thenReturn(List.of(restaurant));

        RestaurantResponse result = restaurantService.filterRestaurants(2L, null).get(0);

        assertEquals("Lazy Address", result.getName());
        assertNull(result.getAddress());
        assertTrue(result.getCuisines().contains("Indian"));
    }

    @Test
    void filterRestaurants_lazyCuisineFailure_stillReturnsRestaurant() {
        when(cacheService.getList(anyString(), eq(RestaurantResponse.class))).thenReturn(Optional.empty());
        Restaurant restaurant = spy(fullRestaurant(12L, "Lazy Cuisine"));
        doThrow(new RuntimeException("could not initialize proxy")).when(restaurant).getCuisines();
        when(restaurantRepository.findByFilters(2L, true)).thenReturn(List.of(restaurant));

        RestaurantResponse result = restaurantService.filterRestaurants(2L, true).get(0);

        assertEquals("Lazy Cuisine", result.getName());
        assertNotNull(result.getAddress());
        assertTrue(result.getCuisines().isEmpty());
    }

    @Test
    void filterRestaurants_bothLazyFailures_returnsBareResponse() {
        when(cacheService.getList(anyString(), eq(RestaurantResponse.class))).thenReturn(Optional.empty());
        Restaurant restaurant = spy(fullRestaurant(13L, "Both Lazy"));
        doThrow(new RuntimeException("lazy address")).when(restaurant).getAddress();
        doThrow(new RuntimeException("lazy cuisines")).when(restaurant).getCuisines();
        when(restaurantRepository.findByFilters(5L, false)).thenReturn(List.of(restaurant));

        RestaurantResponse result = restaurantService.filterRestaurants(5L, false).get(0);

        assertEquals(13L, result.getId());
        assertEquals("Both Lazy", result.getName());
        assertNull(result.getAddress());
        assertTrue(result.getCuisines().isEmpty());
    }

    // ==================== createRestaurant ====================

    @Test
    void createRestaurant_ownerNotFound_throws() {
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(restaurantOwnerRepository.findById(1L)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> restaurantService.createRestaurant(new RestaurantRequest()));
        assertEquals("Owner not found", ex.getMessage());
    }

    @Test
    void createRestaurant_nullFlagsDefaultFalse_noAddressNoCuisines() {
        RestaurantOwner owner = owner(1L);
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(restaurantOwnerRepository.findById(1L)).thenReturn(Optional.of(owner));
        when(restaurantRepository.save(any(Restaurant.class))).thenAnswer(inv -> {
            Restaurant saved = inv.getArgument(0);
            saved.setId(50L);
            return saved;
        });

        RestaurantRequest request = new RestaurantRequest();
        request.setName("New Place");
        request.setDescription("desc");
        request.setImageUrl("img");
        request.setOpeningTime(LocalTime.of(9, 0));
        request.setClosingTime(LocalTime.of(22, 0));
        request.setAverageDeliveryTime(30);
        request.setMinimumOrderAmount(100.0);
        request.setDeliveryFee(20.0);
        request.setFreeDeliveryAvailable(null);
        request.setFreeDeliveryAbove(500.0);
        request.setIsPureVeg(null);
        request.setLicenseNumber("LIC-1");
        request.setFssaiNumber("FSSAI-1");
        request.setFeatures(null);
        request.setAddress(null);
        request.setCuisineIds(null);

        RestaurantResponse response = restaurantService.createRestaurant(request);

        ArgumentCaptor<Restaurant> captor = ArgumentCaptor.forClass(Restaurant.class);
        verify(restaurantRepository).save(captor.capture());
        Restaurant saved = captor.getValue();
        assertEquals(owner, saved.getOwner());
        assertEquals("New Place", saved.getName());
        assertEquals(Boolean.FALSE, saved.getFreeDeliveryAvailable());
        assertEquals(Boolean.FALSE, saved.getIsPureVeg());
        assertNull(saved.getAddress());
        assertEquals(50L, response.getId());
        verify(cacheService).deletePattern("restaurant");
    }

    @Test
    void createRestaurant_emptyCuisineIds_skipsCuisineLookup() {
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(restaurantOwnerRepository.findById(1L)).thenReturn(Optional.of(owner(1L)));
        when(restaurantRepository.save(any(Restaurant.class))).thenAnswer(inv -> inv.getArgument(0));

        RestaurantRequest request = new RestaurantRequest();
        request.setName("Empty Cuisines");
        request.setCuisineIds(new HashSet<>());
        request.setFreeDeliveryAvailable(true);
        request.setIsPureVeg(true);
        request.setFeatures(Set.of("AC", "Parking"));

        restaurantService.createRestaurant(request);

        verify(cuisineRepository, never()).findById(any());
        ArgumentCaptor<Restaurant> captor = ArgumentCaptor.forClass(Restaurant.class);
        verify(restaurantRepository).save(captor.capture());
        assertEquals(Boolean.TRUE, captor.getValue().getFreeDeliveryAvailable());
        assertEquals(Boolean.TRUE, captor.getValue().getIsPureVeg());
        assertEquals(Set.of("AC", "Parking"), captor.getValue().getFeatures());
    }

    @Test
    void createRestaurant_withAddressAndCuisines_persistsBoth() {
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(restaurantOwnerRepository.findById(1L)).thenReturn(Optional.of(owner(1L)));
        Cuisine cuisine = new Cuisine();
        cuisine.setId(3L);
        cuisine.setName("Chinese");
        when(cuisineRepository.findAllById(List.of(3L))).thenReturn(List.of(cuisine));
        when(restaurantRepository.save(any(Restaurant.class))).thenAnswer(inv -> inv.getArgument(0));

        RestaurantRequest request = new RestaurantRequest();
        request.setName("Dragon");
        request.setAddress(addressRequest());
        request.setCuisineIds(Set.of(3L));
        request.setFreeDeliveryAvailable(false);
        request.setIsPureVeg(false);

        RestaurantResponse response = restaurantService.createRestaurant(request);

        ArgumentCaptor<Restaurant> captor = ArgumentCaptor.forClass(Restaurant.class);
        verify(restaurantRepository).save(captor.capture());
        Address address = captor.getValue().getAddress();
        assertEquals("12 MG Road", address.getAddressLine1());
        assertEquals("Suite 2", address.getAddressLine2());
        assertEquals("Bengaluru", address.getCity());
        assertEquals("KA", address.getState());
        assertEquals("560001", address.getPincode());
        assertEquals("Near park", address.getLandmark());
        assertEquals(12.97, address.getLatitude());
        assertEquals(77.59, address.getLongitude());
        assertTrue(captor.getValue().getCuisines().contains(cuisine));
        assertTrue(response.getCuisines().contains("Chinese"));
        assertNotNull(response.getAddress());
    }

    @Test
    void createRestaurant_missingCuisine_throwsResourceNotFound() {
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(restaurantOwnerRepository.findById(1L)).thenReturn(Optional.of(owner(1L)));
        when(cuisineRepository.findAllById(List.of(99L))).thenReturn(List.of());

        RestaurantRequest request = new RestaurantRequest();
        request.setName("Missing Cuisine");
        request.setCuisineIds(Set.of(99L));

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> restaurantService.createRestaurant(request));
        assertEquals("Cuisine not found: 99", ex.getMessage());
        verify(restaurantRepository, never()).save(any());
    }

    // ==================== updateRestaurant ====================

    @Test
    void updateRestaurant_notFound_throws() {
        when(restaurantRepository.findByIdWithDetails(1L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> restaurantService.updateRestaurant(1L, new RestaurantRequest()));
    }

    @Test
    void updateRestaurant_notOwner_throwsUnauthorized() {
        Restaurant restaurant = fullRestaurant(1L, "Mine");
        restaurant.getOwner().setId(10L);
        when(restaurantRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(restaurant));
        when(securityUtils.getCurrentUserId()).thenReturn(99L);

        UnauthorizedException ex = assertThrows(UnauthorizedException.class,
                () -> restaurantService.updateRestaurant(1L, new RestaurantRequest()));
        assertEquals("Not your restaurant", ex.getMessage());
        verify(restaurantRepository, never()).save(any());
    }

    @Test
    void updateRestaurant_allFieldsNullAndNoAddress_savesUnchanged() {
        Restaurant restaurant = fullRestaurant(1L, "Original");
        when(restaurantRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(restaurant));
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(restaurantRepository.save(restaurant)).thenReturn(restaurant);

        RestaurantRequest request = new RestaurantRequest();

        RestaurantResponse response = restaurantService.updateRestaurant(1L, request);

        assertEquals("Original", response.getName());
        verify(restaurantRepository).save(restaurant);
        verify(cacheService).delete(CacheKeyGenerator.restaurant(1L));
        verify(cacheService).deletePattern("restaurant");
    }

    @Test
    void updateRestaurant_allFieldsAndExistingAddress() {
        Restaurant restaurant = fullRestaurant(1L, "Original");
        when(restaurantRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(restaurant));
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(restaurantRepository.save(any(Restaurant.class))).thenAnswer(inv -> inv.getArgument(0));

        RestaurantRequest request = new RestaurantRequest();
        request.setName("Updated");
        request.setDescription("new desc");
        request.setImageUrl("new-img");
        request.setOpeningTime(LocalTime.of(8, 0));
        request.setClosingTime(LocalTime.of(23, 0));
        request.setMinimumOrderAmount(150.0);
        request.setDeliveryFee(25.0);
        request.setIsPureVeg(true);
        request.setFeatures(Set.of("Wifi"));
        request.setAddress(addressRequest());

        RestaurantResponse response = restaurantService.updateRestaurant(1L, request);

        assertEquals("Updated", response.getName());
        assertEquals("new desc", response.getDescription());
        assertEquals("new-img", response.getImageUrl());
        assertEquals(LocalTime.of(8, 0), response.getOpeningTime());
        assertEquals(LocalTime.of(23, 0), response.getClosingTime());
        assertEquals(150.0, response.getMinimumOrderAmount());
        assertEquals(25.0, response.getDeliveryFee());
        assertTrue(response.getIsPureVeg());
        assertEquals(Set.of("Wifi"), response.getFeatures());
        assertEquals("12 MG Road", response.getAddress().getAddressLine1());
        assertEquals("Bengaluru", response.getAddress().getCity());
    }

    @Test
    void updateRestaurant_createsAddressWhenMissing() {
        Restaurant restaurant = fullRestaurant(1L, "No Addr");
        restaurant.setAddress(null);
        when(restaurantRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(restaurant));
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(restaurantRepository.save(any(Restaurant.class))).thenAnswer(inv -> inv.getArgument(0));

        RestaurantRequest request = new RestaurantRequest();
        request.setAddress(addressRequest());

        RestaurantResponse response = restaurantService.updateRestaurant(1L, request);

        assertNotNull(response.getAddress());
        assertEquals("12 MG Road", response.getAddress().getAddressLine1());
        assertEquals(12.97, response.getAddress().getLatitude());
    }

    // ==================== deleteRestaurant ====================

    @Test
    void deleteRestaurant_notFound_throws() {
        when(restaurantRepository.findByIdWithDetails(1L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> restaurantService.deleteRestaurant(1L));
    }

    @Test
    void deleteRestaurant_notOwner_throwsUnauthorized() {
        Restaurant restaurant = fullRestaurant(1L, "X");
        restaurant.getOwner().setId(2L);
        when(restaurantRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(restaurant));
        when(securityUtils.getCurrentUserId()).thenReturn(1L);

        assertThrows(UnauthorizedException.class, () -> restaurantService.deleteRestaurant(1L));
        verify(restaurantRepository, never()).save(any());
    }

    @Test
    void deleteRestaurant_softDeletesAndInvalidatesCache() {
        Restaurant restaurant = fullRestaurant(1L, "X");
        restaurant.setIsActive(true);
        when(restaurantRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(restaurant));
        when(securityUtils.getCurrentUserId()).thenReturn(1L);

        restaurantService.deleteRestaurant(1L);

        assertEquals(Boolean.FALSE, restaurant.getIsActive());
        verify(restaurantRepository).save(restaurant);
        verify(cacheService).delete(CacheKeyGenerator.restaurant(1L));
        verify(cacheService).deletePattern("restaurant");
    }

    // ==================== toggleRestaurantStatus ====================

    @Test
    void toggleRestaurantStatus_notFound_throws() {
        when(restaurantRepository.findByIdWithDetails(1L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class,
                () -> restaurantService.toggleRestaurantStatus(1L, true));
    }

    @Test
    void toggleRestaurantStatus_notOwner_throwsUnauthorized() {
        Restaurant restaurant = fullRestaurant(1L, "X");
        restaurant.getOwner().setId(5L);
        when(restaurantRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(restaurant));
        when(securityUtils.getCurrentUserId()).thenReturn(1L);

        assertThrows(UnauthorizedException.class,
                () -> restaurantService.toggleRestaurantStatus(1L, false));
    }

    @Test
    void toggleRestaurantStatus_setsIsOpenAndInvalidatesCache() {
        Restaurant restaurant = fullRestaurant(1L, "X");
        restaurant.setIsOpen(true);
        when(restaurantRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(restaurant));
        when(securityUtils.getCurrentUserId()).thenReturn(1L);

        restaurantService.toggleRestaurantStatus(1L, false);

        assertEquals(Boolean.FALSE, restaurant.getIsOpen());
        verify(restaurantRepository).save(restaurant);
        verify(cacheService).delete(CacheKeyGenerator.restaurant(1L));
        verify(cacheService).deletePattern("restaurant");
    }

    @Test
    void toggleRestaurantStatus_openTrue() {
        Restaurant restaurant = fullRestaurant(1L, "X");
        restaurant.setIsOpen(false);
        when(restaurantRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(restaurant));
        when(securityUtils.getCurrentUserId()).thenReturn(1L);

        restaurantService.toggleRestaurantStatus(1L, true);

        assertEquals(Boolean.TRUE, restaurant.getIsOpen());
    }

    // ==================== updateRestaurantRating ====================

    @Test
    void updateRestaurantRating_onlyInvalidatesCaches() {
        restaurantService.updateRestaurantRating(42L);

        verify(cacheService).delete(CacheKeyGenerator.restaurant(42L));
        verify(cacheService).deletePattern("restaurant");
        verifyNoInteractions(restaurantRepository);
    }

    // ==================== helpers ====================

    private RestaurantOwner owner(Long id) {
        RestaurantOwner owner = new RestaurantOwner();
        owner.setId(id);
        owner.setFullName("Owner");
        return owner;
    }

    private Address address() {
        Address address = new Address();
        address.setId(20L);
        address.setAddressLine1("12 MG Road");
        address.setAddressLine2("Suite 2");
        address.setCity("Bengaluru");
        address.setState("KA");
        address.setPincode("560001");
        address.setLandmark("Near park");
        address.setLatitude(12.97);
        address.setLongitude(77.59);
        return address;
    }

    private AddressRequest addressRequest() {
        AddressRequest request = new AddressRequest();
        request.setAddressLine1("12 MG Road");
        request.setAddressLine2("Suite 2");
        request.setCity("Bengaluru");
        request.setState("KA");
        request.setPincode("560001");
        request.setLandmark("Near park");
        request.setLatitude(12.97);
        request.setLongitude(77.59);
        return request;
    }

    @Test
    void getRestaurantsByIds_emptyIds_returnsEmptyList() {
        assertTrue(restaurantService.getRestaurantsByIds(List.of()).isEmpty());
        assertTrue(restaurantService.getRestaurantsByIds(null).isEmpty());
        verify(restaurantRepository, never()).findAllById(any());
    }

    @Test
    void getRestaurantsByIds_withIds_mapsRestaurants() {
        Restaurant r = fullRestaurant(1L, "Test Restaurant");
        // Batch B fix: N+1 eliminated — service now uses a batch fetch-join.
        when(restaurantRepository.findAllByIdsWithDetails(List.of(1L))).thenReturn(List.of(r));

        var result = restaurantService.getRestaurantsByIds(List.of(1L));

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).getId());
        verify(restaurantRepository).findAllByIdsWithDetails(List.of(1L));
    }

    private Restaurant fullRestaurant(Long id, String name) {
        Cuisine cuisine = new Cuisine();
        cuisine.setId(3L);
        cuisine.setName("Indian");

        Restaurant restaurant = new Restaurant();
        restaurant.setId(id);
        restaurant.setName(name);
        restaurant.setDescription("Best biryani");
        restaurant.setOwner(owner(1L));
        restaurant.setAddress(address());
        restaurant.setCuisines(Set.of(cuisine));
        restaurant.setImageUrl("img");
        restaurant.setOpeningTime(LocalTime.of(10, 0));
        restaurant.setClosingTime(LocalTime.of(22, 0));
        restaurant.setIsOpen(true);
        restaurant.setIsActive(true);
        restaurant.setAverageRating(4.5);
        restaurant.setTotalReviews(10);
        restaurant.setAverageDeliveryTime(30);
        restaurant.setMinimumOrderAmount(99.0);
        restaurant.setDeliveryFee(15.0);
        restaurant.setFreeDeliveryAvailable(true);
        restaurant.setFreeDeliveryAbove(399.0);
        restaurant.setIsPureVeg(false);
        restaurant.setFeatures(Set.of("AC"));
        return restaurant;
    }

    // ==================== findNearbyRestaurants ====================

    @Test
    void findNearbyRestaurants_geoIndexHits_mapsResponses() {
        // The service adds PROXIMITY_RADIUS_EPSILON_KM to the radius before
        // calling the geo index — use matchers that tolerate this.
        when(restaurantGeoIndexService.findNearbyRestaurantIds(
                eq(12.97), eq(77.59), anyDouble(), eq(10)))
                .thenReturn(List.of(1L));
        Restaurant r = fullRestaurant(1L, "Geo Hub");
        when(restaurantRepository.findAllByIdsWithDetails(List.of(1L))).thenReturn(List.of(r));

        List<RestaurantResponse> result =
                restaurantService.findNearbyRestaurants(12.97, 77.59, 5.0, 10);

        assertEquals(1, result.size());
        assertEquals("Geo Hub", result.get(0).getName());
        verify(restaurantRepository, never()).findNearbyRestaurantIds(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyInt());
    }

    @Test
    void findNearbyRestaurants_geoIndexEmpty_fallsBackToDbQuery() {
        when(restaurantGeoIndexService.findNearbyRestaurantIds(anyDouble(), anyDouble(), anyDouble(), anyInt()))
                .thenReturn(List.of());
        when(restaurantRepository.findNearbyRestaurantIds(eq(12.97), eq(77.59), anyDouble(), anyDouble(), eq(5.0), anyDouble(), eq(10)))
                .thenReturn(List.of(2L));
        when(restaurantRepository.findAllByIdsWithDetails(List.of(2L)))
                .thenReturn(List.of(fullRestaurant(2L, "DB Hub")));

        List<RestaurantResponse> result =
                restaurantService.findNearbyRestaurants(12.97, 77.59, 5.0, 10);

        assertEquals(1, result.size());
        assertEquals("DB Hub", result.get(0).getName());
    }

    @Test
    void findNearbyRestaurants_noRestaurantsAnywhere_returnsEmpty() {
        when(restaurantGeoIndexService.findNearbyRestaurantIds(anyDouble(), anyDouble(), anyDouble(), anyInt()))
                .thenReturn(List.of());
        when(restaurantRepository.findNearbyRestaurantIds(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyInt()))
                .thenReturn(List.of());

        List<RestaurantResponse> result =
                restaurantService.findNearbyRestaurants(12.97, 77.59, 5.0, 10);

        assertTrue(result.isEmpty());
    }

    @Test
    void findNearbyRestaurants_staleGeoId_droppedByMapLookup() {
        when(restaurantGeoIndexService.findNearbyRestaurantIds(anyDouble(), anyDouble(), anyDouble(), anyInt()))
                .thenReturn(List.of(1L, 99L));
        // Only id=1 still exists; id=99 was removed but lingers in the geo index
        when(restaurantRepository.findAllByIdsWithDetails(List.of(1L, 99L)))
                .thenReturn(List.of(fullRestaurant(1L, "Alive")));

        List<RestaurantResponse> result =
                restaurantService.findNearbyRestaurants(12.97, 77.59, 5.0, 10);

        assertEquals(1, result.size());
    }

    // ==================== getActiveRestaurantsInRadius / proximity ====================

    @Test
    void getActiveRestaurantsInRadius_delegatesToFindNearbyRestaurants() {
        // The service adds PROXIMITY_RADIUS_EPSILON_KM to the radius — use matchers.
        when(restaurantGeoIndexService.findNearbyRestaurantIds(eq(12.97), eq(77.59), anyDouble(), eq(50)))
                .thenReturn(List.of(1L));
        when(restaurantRepository.findAllByIdsWithDetails(List.of(1L)))
                .thenReturn(List.of(fullRestaurant(1L, "Nearby")));

        List<RestaurantResponse> result =
                restaurantService.getActiveRestaurantsInRadius(12.97, 77.59, 5.0);

        assertEquals(1, result.size());
        assertEquals("Nearby", result.get(0).getName());
        // distanceKm should be computed and set (restaurant address is at 12.97,77.59)
        assertEquals(0.0, result.get(0).getDistanceKm(), 0.1);
    }

    @Test
    void getAllActiveRestaurants_withLocation_delegatesToNearbyPath() {
        when(restaurantGeoIndexService.findNearbyRestaurantIds(eq(12.97), eq(77.59), anyDouble(), eq(50)))
                .thenReturn(List.of(2L));
        when(restaurantRepository.findAllByIdsWithDetails(List.of(2L)))
                .thenReturn(List.of(fullRestaurant(2L, "Zone Restaurant")));

        List<RestaurantResponse> result =
                restaurantService.getAllActiveRestaurants(null, 12.97, 77.59, 5.0);

        assertEquals(1, result.size());
        assertEquals("Zone Restaurant", result.get(0).getName());
        verify(restaurantRepository, never()).findAllActiveWithDetails();
    }

    @Test
    void getAllActiveRestaurants_withoutLocation_fallsBackToGlobalList() {
        List<RestaurantResponse> cached = List.of(
                RestaurantResponse.builder().id(1L).name("Global A").build());
        when(cacheService.getList(CacheKeyGenerator.restaurantList(), RestaurantResponse.class))
                .thenReturn(Optional.of(cached));

        List<RestaurantResponse> result =
                restaurantService.getAllActiveRestaurants(null, null, null, null);

        assertSame(cached, result);
        verify(restaurantRepository, never()).findAllActiveWithDetails();
    }

    @Test
    void getAllActiveRestaurants_partialLocation_returnsGlobalList() {
        // lat present but lon/radius null -> should NOT delegate to nearby path
        when(cacheService.getList(CacheKeyGenerator.restaurantList(), RestaurantResponse.class))
                .thenReturn(Optional.of(List.of(
                        RestaurantResponse.builder().id(1L).name("Should Be Returned").build())));

        List<RestaurantResponse> result =
                restaurantService.getAllActiveRestaurants(null, 12.97, null, null);

        assertEquals(1, result.size());
        verify(restaurantRepository, never()).findNearbyRestaurantIds(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyInt());
    }

    @Test
    void findNearbyRestaurants_distanceKmSetOnResponse() {
        // Restaurant at 12.97, 77.59 (same as query coords) -> distance ~0.
        // The service adds PROXIMITY_RADIUS_EPSILON_KM to the radius.
        when(restaurantGeoIndexService.findNearbyRestaurantIds(
                eq(12.97), eq(77.59), anyDouble(), eq(10)))
                .thenReturn(List.of(1L));
        Restaurant r = spy(fullRestaurant(1L, "AtLocation"));
        when(restaurantRepository.findAllByIdsWithDetails(List.of(1L)))
                .thenReturn(List.of(r));

        List<RestaurantResponse> result =
                restaurantService.findNearbyRestaurants(12.97, 77.59, 5.0, 10);

        assertNotNull(result.get(0).getDistanceKm());
        assertEquals(0.0, result.get(0).getDistanceKm(), 0.01);
    }

    @Test
    void findNearbyRestaurants_distanceKmNullWhenNoAddress() {
        when(restaurantGeoIndexService.findNearbyRestaurantIds(
                eq(12.97), eq(77.59), anyDouble(), eq(10)))
                .thenReturn(List.of(3L));
        Restaurant restaurantWithoutAddress = new Restaurant();
        restaurantWithoutAddress.setId(3L);
        restaurantWithoutAddress.setName("No Address");
        restaurantWithoutAddress.setIsActive(true);
        restaurantWithoutAddress.setIsOpen(true);
        restaurantWithoutAddress.setIsPureVeg(false);
        restaurantWithoutAddress.setAverageRating(4.0);
        restaurantWithoutAddress.setAverageDeliveryTime(30);
        restaurantWithoutAddress.setMinimumOrderAmount(100.0);
        restaurantWithoutAddress.setDeliveryFee(20.0);
        restaurantWithoutAddress.setFeatures(Set.of());
        restaurantWithoutRestaurantCuisines(restaurantWithoutAddress);

        when(restaurantRepository.findAllByIdsWithDetails(List.of(3L)))
                .thenReturn(List.of(restaurantWithoutAddress));

        List<RestaurantResponse> result =
                restaurantService.findNearbyRestaurants(12.97, 77.59, 5.0, 10);

        assertEquals(1, result.size());
        assertNull(result.get(0).getDistanceKm());
    }

    private void restaurantWithoutRestaurantCuisines(Restaurant r) {
        // Ensure cuisines set is initialized to avoid NPE in mapping
        r.setCuisines(Set.of());
    }

    // ==================== findTopRatedNearbyRestaurants ====================

    @Test
    void findTopRatedNearbyRestaurants_geoIndexHits_returnsSortedByRating() {
        // The service adds PROXIMITY_RADIUS_EPSILON_KM to the radius for the
        // geo index — use eq for lat/lon/limit, anyDouble for radius.
        when(restaurantGeoIndexService.findNearbyRestaurantIds(
                eq(12.97), eq(77.59), anyDouble(), eq(10)))
                .thenReturn(List.of(1L, 2L));

        Restaurant r1 = fullRestaurant(1L, "Better Rated");
        r1.setAverageRating(4.5);
        r1.setTotalReviews(100);
        Restaurant r2 = fullRestaurant(2L, "Lower Rated");
        r2.setAverageRating(3.8);
        r2.setTotalReviews(50);

        when(restaurantRepository.findAllByIdsWithDetails(List.of(1L, 2L)))
                .thenReturn(List.of(r1, r2));

        List<RestaurantResponse> result =
                restaurantService.findTopRatedNearbyRestaurants(12.97, 77.59, 5.0, 10);

        assertEquals(2, result.size());
        // Higher-rated restaurant should come first
        assertEquals("Better Rated", result.get(0).getName());
        assertEquals("Lower Rated", result.get(1).getName());
    }

    @Test
    void findTopRatedNearbyRestaurants_tieBreakByTotalReviews() {
        when(restaurantGeoIndexService.findNearbyRestaurantIds(
                eq(12.97), eq(77.59), anyDouble(), eq(10)))
                .thenReturn(List.of(1L, 2L));

        // Same rating, different review counts — more reviews should rank higher
        Restaurant r1 = fullRestaurant(1L, "Four Stars Few Reviews");
        r1.setAverageRating(4.3);
        r1.setTotalReviews(30);
        Restaurant r2 = fullRestaurant(2L, "Four Stars Many Reviews");
        r2.setAverageRating(4.3);
        r2.setTotalReviews(200);

        when(restaurantRepository.findAllByIdsWithDetails(List.of(1L, 2L)))
                .thenReturn(List.of(r1, r2));

        List<RestaurantResponse> result =
                restaurantService.findTopRatedNearbyRestaurants(12.97, 77.59, 5.0, 10);

        assertEquals(2, result.size());
        assertEquals("Four Stars Many Reviews", result.get(0).getName());
        assertEquals("Four Stars Few Reviews", result.get(1).getName());
    }

    @Test
    void findTopRatedNearbyRestaurants_nullRatingTreatedAsZero() {
        when(restaurantGeoIndexService.findNearbyRestaurantIds(
                eq(12.97), eq(77.59), anyDouble(), eq(10)))
                .thenReturn(List.of(1L, 2L));

        Restaurant r1 = fullRestaurant(1L, "No Rating");
        r1.setAverageRating(null);
        r1.setTotalReviews(null);
        Restaurant r2 = fullRestaurant(2L, "Has Rating");
        r2.setAverageRating(4.7);
        r2.setTotalReviews(50);

        when(restaurantRepository.findAllByIdsWithDetails(List.of(1L, 2L)))
                .thenReturn(List.of(r1, r2));

        List<RestaurantResponse> result =
                restaurantService.findTopRatedNearbyRestaurants(12.97, 77.59, 5.0, 10);

        assertEquals(2, result.size());
        assertEquals("Has Rating", result.get(0).getName());
        assertEquals("No Rating", result.get(1).getName());
    }

    @Test
    void findTopRatedNearbyRestaurants_emptyGeoIndex_fallsBackToDbQuery() {
        // Geo index returns empty → SQL fallback path runs
        when(restaurantGeoIndexService.findNearbyRestaurantIds(
                anyDouble(), anyDouble(), anyDouble(), anyInt()))
                .thenReturn(List.of());
        when(restaurantRepository.findNearbyRestaurantIds(
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyInt()))
                .thenReturn(List.of(1L, 2L));

        Restaurant r1 = fullRestaurant(1L, "Top");
        r1.setAverageRating(4.9);
        r1.setTotalReviews(10);
        Restaurant r2 = fullRestaurant(2L, "Second");
        r2.setAverageRating(4.2);
        r2.setTotalReviews(5);

        when(restaurantRepository.findAllByIdsWithDetails(List.of(1L, 2L)))
                .thenReturn(List.of(r1, r2));

        List<RestaurantResponse> result =
                restaurantService.findTopRatedNearbyRestaurants(12.97, 77.59, 5.0, 10);

        assertEquals(2, result.size());
        assertEquals("Top", result.get(0).getName());
    }
}
