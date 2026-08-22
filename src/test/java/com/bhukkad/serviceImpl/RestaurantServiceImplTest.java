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

        lenient().when(cacheService.getListOrCompute(anyString(), any(Class.class), anyLong(), any()))
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

        lenient().when(cacheService.getOrCompute(anyString(), any(Class.class), anyLong(), any()))
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
}
