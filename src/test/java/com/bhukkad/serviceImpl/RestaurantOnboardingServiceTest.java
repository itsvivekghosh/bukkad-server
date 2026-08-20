package com.bhukkad.serviceImpl;

import com.bhukkad.cache.RedisCacheService;
import com.bhukkad.dto.request.AddressRequest;
import com.bhukkad.dto.request.RestaurantRequest;
import com.bhukkad.entity.Address;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.entity.RestaurantOwner;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.mapper.AddressMapper;
import com.bhukkad.repository.CuisineRepository;
import com.bhukkad.repository.RestaurantOwnerRepository;
import com.bhukkad.repository.RestaurantRepository;
import com.bhukkad.security.SecurityUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RestaurantOnboardingServiceTest {

    @Mock
    private RestaurantRepository restaurantRepository;
    @Mock
    private RestaurantOwnerRepository restaurantOwnerRepository;
    @Mock
    private CuisineRepository cuisineRepository;
    @Mock
    private SecurityUtils securityUtils;
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
    }

    private RestaurantRequest onboardingRequest(boolean withLicense) {
        RestaurantRequest request = new RestaurantRequest();
        request.setName("Dark Kitchen Test");
        request.setDescription("Cloud kitchen");
        request.setOpeningTime(LocalTime.of(10, 0));
        request.setClosingTime(LocalTime.of(23, 0));
        if (withLicense) {
            request.setLicenseNumber("LIC-123");
            request.setFssaiNumber("FSSAI-456");
        }
        request.setVirtualBrandName("Test Brand");
        request.setTenantId(3L);
        AddressRequest address = new AddressRequest();
        address.setAddressLine1("1 Kitchen Lane");
        address.setCity("Pune");
        address.setState("Maharashtra");
        address.setPincode("411001");
        address.setLatitude(18.5);
        address.setLongitude(73.8);
        request.setAddress(address);
        return request;
    }

    private RestaurantOwner owner(long id) {
        RestaurantOwner owner = new RestaurantOwner();
        owner.setId(id);
        return owner;
    }

    @Test
    void createOnboardingApplication_missingLicense_throwsBusinessException() {
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(restaurantOwnerRepository.findById(1L)).thenReturn(Optional.of(owner(1L)));

        assertThrows(BusinessException.class,
                () -> restaurantService.createOnboardingApplication(onboardingRequest(false)));
        verify(restaurantRepository, never()).save(any());
    }

    @Test
    void createOnboardingApplication_startsPendingVerification() {
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(restaurantOwnerRepository.findById(1L)).thenReturn(Optional.of(owner(1L)));
        when(restaurantRepository.save(any(Restaurant.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = restaurantService.createOnboardingApplication(onboardingRequest(true));

        assertEquals("PENDING_VERIFICATION", response.getOnboardingStatus());
        assertEquals(3L, response.getTenantId());
        assertEquals("Dark Kitchen Test", response.getName());
    }

    @Test
    void createOnboardingApplication_ownerNotFound_throwsNotFound() {
        when(securityUtils.getCurrentUserId()).thenReturn(99L);
        when(restaurantOwnerRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> restaurantService.createOnboardingApplication(onboardingRequest(true)));
    }

    @Test
    void getOnboardingStatus_returnsAllOwnedRestaurants() {
        RestaurantOwner owner = owner(1L);
        Restaurant pending = new Restaurant();
        pending.setId(1L);
        pending.setName("Pending Kitchen");
        pending.setOwner(owner);
        pending.setOnboardingStatus(Restaurant.OnboardingStatus.PENDING_VERIFICATION);

        Restaurant approved = new Restaurant();
        approved.setId(2L);
        approved.setName("Approved Kitchen");
        approved.setOwner(owner);
        approved.setOnboardingStatus(Restaurant.OnboardingStatus.APPROVED);

        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(restaurantRepository.findByOwnerIdWithDetails(1L)).thenReturn(List.of(pending, approved));

        var status = restaurantService.getOnboardingStatus();

        assertEquals(2, status.getRestaurants().size());
        assertEquals("PENDING_VERIFICATION", status.getRestaurants().get(0).getOnboardingStatus());
        assertEquals("APPROVED", status.getRestaurants().get(1).getOnboardingStatus());
    }

    @Test
    void reviewOnboarding_approve_setsApprovedAndActive() {
        Restaurant restaurant = new Restaurant();
        restaurant.setId(1L);
        restaurant.setName("Kitchen");
        restaurant.setOnboardingStatus(Restaurant.OnboardingStatus.PENDING_VERIFICATION);
        restaurant.setIsActive(false);

        when(restaurantRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(restaurant));
        when(restaurantRepository.save(any(Restaurant.class))).thenAnswer(inv -> inv.getArgument(0));

        restaurantService.reviewOnboarding(1L, true, null);

        assertEquals(Restaurant.OnboardingStatus.APPROVED, restaurant.getOnboardingStatus());
        assertTrue(restaurant.getIsActive());
        assertNull(restaurant.getOnboardingRejectionReason());
    }

    @Test
    void reviewOnboarding_rejectWithoutReason_throwsBusinessException() {
        Restaurant restaurant = new Restaurant();
        restaurant.setId(1L);
        restaurant.setOnboardingStatus(Restaurant.OnboardingStatus.PENDING_VERIFICATION);

        when(restaurantRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(restaurant));

        assertThrows(BusinessException.class, () -> restaurantService.reviewOnboarding(1L, false, null));
        verify(restaurantRepository, never()).save(any());
    }

    @Test
    void reviewOnboarding_reject_deactivatesWithReason() {
        Restaurant restaurant = new Restaurant();
        restaurant.setId(1L);
        restaurant.setName("Kitchen");
        restaurant.setOnboardingStatus(Restaurant.OnboardingStatus.PENDING_VERIFICATION);
        restaurant.setIsActive(true);

        when(restaurantRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(restaurant));
        when(restaurantRepository.save(any(Restaurant.class))).thenAnswer(inv -> inv.getArgument(0));

        restaurantService.reviewOnboarding(1L, false, "Missing FSSAI documents");

        assertEquals(Restaurant.OnboardingStatus.REJECTED, restaurant.getOnboardingStatus());
        assertFalse(restaurant.getIsActive());
        assertEquals("Missing FSSAI documents", restaurant.getOnboardingRejectionReason());
    }

    @Test
    void reviewOnboarding_unknownRestaurant_throwsNotFound() {
        when(restaurantRepository.findByIdWithDetails(99L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> restaurantService.reviewOnboarding(99L, true, null));
    }

    @Test
    void getAllActiveRestaurants_excludesRejectedAndSuspended() {
        Restaurant approved = new Restaurant();
        approved.setId(1L);
        approved.setOnboardingStatus(Restaurant.OnboardingStatus.APPROVED);
        approved.setTenantId(3L);

        Restaurant rejected = new Restaurant();
        rejected.setId(2L);
        rejected.setOnboardingStatus(Restaurant.OnboardingStatus.REJECTED);
        rejected.setTenantId(3L);

        Restaurant otherTenant = new Restaurant();
        otherTenant.setId(3L);
        otherTenant.setOnboardingStatus(Restaurant.OnboardingStatus.APPROVED);
        otherTenant.setTenantId(9L);

        when(restaurantRepository.findAllActiveWithDetails())
                .thenReturn(List.of(approved, rejected, otherTenant));

        var result = restaurantService.getAllActiveRestaurants(3L);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).getId());
    }
}
