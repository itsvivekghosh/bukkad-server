package com.bhukkad.controller;

import com.bhukkad.dto.request.RestaurantBusyModeRequest;
import com.bhukkad.dto.request.RestaurantRequest;
import com.bhukkad.dto.request.ReviewResponseRequest;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.CursorPagedResponse;
import com.bhukkad.dto.response.PagedResponse;
import com.bhukkad.dto.response.RestaurantAnalyticsResponse;
import com.bhukkad.dto.response.RestaurantDashboardResponse;
import com.bhukkad.dto.response.RestaurantOnboardingStatusResponse;
import com.bhukkad.dto.response.RestaurantResponse;
import com.bhukkad.dto.response.RestaurantSettlementResponse;
import com.bhukkad.entity.Review;
import com.bhukkad.restaurant.RestaurantBusyService;
import com.bhukkad.restaurant.RestaurantDashboardService;
import com.bhukkad.service.RestaurantAnalyticsService;
import com.bhukkad.service.RestaurantService;
import com.bhukkad.service.ReviewService;
import com.bhukkad.settlement.RestaurantSettlementService;
import com.bhukkad.cache.http.HttpCacheSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Tag;

@Tag("regression")
@ExtendWith(MockitoExtension.class)
public class RestaurantControllerTest {

    @Mock
    private RestaurantService restaurantService;

    /** Backs the V17 owner-response endpoint; the other tests never touch it. */
    @Mock
    private ReviewService reviewService;

    @Mock
    private HttpCacheSupport httpCacheSupport;

    @Mock
    private RestaurantAnalyticsService restaurantAnalyticsService;

    @Mock
    private RestaurantSettlementService restaurantSettlementService;

    @Mock
    private RestaurantBusyService restaurantBusyService;

    @Mock
    private RestaurantDashboardService restaurantDashboardService;

    @InjectMocks
    private RestaurantController restaurantController;

    @Test
    void getAllRestaurants_returnsActiveRestaurants() {
        List<RestaurantResponse> restaurants = List.of(new RestaurantResponse());
        when(restaurantService.getAllActiveRestaurants(null)).thenReturn(restaurants);
        when(httpCacheSupport.buildCacheHeaders(anyString(), anyString())).thenReturn(new org.springframework.http.HttpHeaders());

        ResponseEntity<ApiResponse<List<RestaurantResponse>>> response = restaurantController.getAllRestaurants(null, null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(restaurants, response.getBody().getData());
        verify(restaurantService).getAllActiveRestaurants(null);
    }

    @Test
    void getRestaurantById_returnsRestaurant() {
        RestaurantResponse restaurant = new RestaurantResponse();
        when(restaurantService.getRestaurantById(1L)).thenReturn(restaurant);
        when(httpCacheSupport.buildCacheHeaders(anyString(), anyString())).thenReturn(new org.springframework.http.HttpHeaders());

        ResponseEntity<ApiResponse<RestaurantResponse>> response = restaurantController.getRestaurantById(1L, null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(restaurant, response.getBody().getData());
    }

    @Test
    void searchRestaurants_returnsMatches() {
        List<RestaurantResponse> restaurants = List.of(new RestaurantResponse());
        when(restaurantService.searchRestaurants("pizza")).thenReturn(restaurants);

        ResponseEntity<ApiResponse<List<RestaurantResponse>>> response =
                restaurantController.searchRestaurants("pizza");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(restaurants, response.getBody().getData());
    }

    @Test
    void filterRestaurants_returnsFiltered() {
        List<RestaurantResponse> restaurants = List.of(new RestaurantResponse());
        when(restaurantService.filterRestaurants(2L, true)).thenReturn(restaurants);

        ResponseEntity<ApiResponse<List<RestaurantResponse>>> response =
                restaurantController.filterRestaurants(2L, true);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(restaurants, response.getBody().getData());
    }

    @Test
    void createRestaurant_returnsCreated() {
        RestaurantRequest request = new RestaurantRequest();
        RestaurantResponse restaurant = new RestaurantResponse();
        when(restaurantService.createRestaurant(request)).thenReturn(restaurant);

        ResponseEntity<ApiResponse<RestaurantResponse>> response = restaurantController.createRestaurant(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Restaurant created successfully", response.getBody().getMessage());
        assertEquals(restaurant, response.getBody().getData());
    }

    @Test
    void getMyRestaurants_returnsOwnerRestaurants() {
        List<RestaurantResponse> restaurants = List.of(new RestaurantResponse());
        when(restaurantService.getMyRestaurants()).thenReturn(restaurants);

        ResponseEntity<ApiResponse<List<RestaurantResponse>>> response = restaurantController.getMyRestaurants();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(restaurants, response.getBody().getData());
    }

    @Test
    void updateRestaurant_returnsUpdated() {
        RestaurantRequest request = new RestaurantRequest();
        RestaurantResponse restaurant = new RestaurantResponse();
        when(restaurantService.updateRestaurant(1L, request)).thenReturn(restaurant);

        ResponseEntity<ApiResponse<RestaurantResponse>> response =
                restaurantController.updateRestaurant(1L, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Restaurant updated successfully", response.getBody().getMessage());
        assertEquals(restaurant, response.getBody().getData());
    }

    @Test
    void deleteRestaurant_returnsSuccess() {
        ResponseEntity<ApiResponse<Void>> response = restaurantController.deleteRestaurant(1L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Restaurant deleted successfully", response.getBody().getMessage());
        verify(restaurantService).deleteRestaurant(1L);
    }

    @Test
    void toggleRestaurantStatus_returnsSuccess() {
        ResponseEntity<ApiResponse<Void>> response = restaurantController.toggleRestaurantStatus(1L, false);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Restaurant status updated", response.getBody().getMessage());
        verify(restaurantService).toggleRestaurantStatus(1L, false);
    }

    /**
     * V17 review moderation: the owner reply endpoint forwards the validated body text to
     * {@link ReviewService#respondToReview(Long, String)} and echoes the saved review back.
     * Ownership enforcement lives in the service, so nothing about it is asserted here.
     */
    @Test
    void respondToReview_returnsUpdatedReview() {
        ReviewResponseRequest request = new ReviewResponseRequest();
        request.setResponse("Thanks for the feedback!");
        Review review = new Review();
        when(reviewService.respondToReview(7L, "Thanks for the feedback!")).thenReturn(review);

        ResponseEntity<ApiResponse<Review>> response = restaurantController.respondToReview(7L, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Response added to review", response.getBody().getMessage());
        assertSame(review, response.getBody().getData());
        verify(reviewService).respondToReview(7L, "Thanks for the feedback!");
    }

    @Test
    void getAllRestaurants_returns304WhenNotModified() {
        List<RestaurantResponse> restaurants = List.of(new RestaurantResponse());
        when(restaurantService.getAllActiveRestaurants(null)).thenReturn(restaurants);
        HttpHeaders headers = new HttpHeaders();
        headers.setETag("W/\"abc\"");
        when(httpCacheSupport.buildCacheHeaders(anyString(), anyString())).thenReturn(headers);
        when(httpCacheSupport.isNotModified("W/\"abc\"", "W/\"abc\"")).thenReturn(true);

        ResponseEntity<ApiResponse<List<RestaurantResponse>>> response =
                restaurantController.getAllRestaurants("W/\"abc\"", null, null);

        assertEquals(HttpStatus.NOT_MODIFIED, response.getStatusCode());
    }

    @Test
    void getRestaurantById_returns304WhenNotModified() {
        RestaurantResponse restaurant = new RestaurantResponse();
        when(restaurantService.getRestaurantById(1L)).thenReturn(restaurant);
        HttpHeaders headers = new HttpHeaders();
        headers.setETag("W/\"def\"");
        when(httpCacheSupport.buildCacheHeaders(anyString(), anyString())).thenReturn(headers);
        when(httpCacheSupport.isNotModified("W/\"def\"", "W/\"def\"")).thenReturn(true);

        ResponseEntity<ApiResponse<RestaurantResponse>> response =
                restaurantController.getRestaurantById(1L, "W/\"def\"", null);

        assertEquals(HttpStatus.NOT_MODIFIED, response.getStatusCode());
    }

    @Test
    void findNearbyRestaurants_returnsNearby() {
        List<RestaurantResponse> restaurants = List.of(new RestaurantResponse());
        when(restaurantService.findNearbyRestaurants(12.9, 77.6, 5.0, 20)).thenReturn(restaurants);

        ResponseEntity<ApiResponse<List<RestaurantResponse>>> response =
                restaurantController.findNearbyRestaurants(12.9, 77.6, 5.0, 20);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(restaurants, response.getBody().getData());
    }

    @Test
    void onboardingSignup_returnsApplication() {
        RestaurantRequest request = new RestaurantRequest();
        RestaurantResponse restaurant = new RestaurantResponse();
        when(restaurantService.createOnboardingApplication(request)).thenReturn(restaurant);

        ResponseEntity<ApiResponse<RestaurantResponse>> response = restaurantController.onboardingSignup(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Onboarding application submitted for verification", response.getBody().getMessage());
        assertEquals(restaurant, response.getBody().getData());
    }

    @Test
    void onboardingStatus_returnsStatus() {
        RestaurantOnboardingStatusResponse status = RestaurantOnboardingStatusResponse.builder().build();
        when(restaurantService.getOnboardingStatus()).thenReturn(status);

        ResponseEntity<ApiResponse<RestaurantOnboardingStatusResponse>> response = restaurantController.onboardingStatus();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(status, response.getBody().getData());
    }

    @Test
    void getRestaurantAnalytics_returnsAnalytics() {
        RestaurantAnalyticsResponse analytics = RestaurantAnalyticsResponse.builder().build();
        when(restaurantAnalyticsService.getAnalytics(1L, 30)).thenReturn(analytics);

        ResponseEntity<ApiResponse<RestaurantAnalyticsResponse>> response =
                restaurantController.getRestaurantAnalytics(1L, 30);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(analytics, response.getBody().getData());
    }

    @Test
    void getSettlements_returnsPagedSettlements() {
        PagedResponse<RestaurantSettlementResponse> page =
                PagedResponse.<RestaurantSettlementResponse>builder().build();
        when(restaurantSettlementService.getRestaurantSettlements(1L, 0, 20)).thenReturn(page);

        ResponseEntity<ApiResponse<PagedResponse<RestaurantSettlementResponse>>> response =
                restaurantController.getSettlements(1L, 0, 20);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(page, response.getBody().getData());
    }

    @Test
    void getSettlementsByCursor_returnsCursorPage() {
        CursorPagedResponse<RestaurantSettlementResponse> page =
                CursorPagedResponse.<RestaurantSettlementResponse>builder().build();
        when(restaurantSettlementService.getRestaurantSettlementsByCursor(1L, "c1", 25)).thenReturn(page);

        ResponseEntity<ApiResponse<CursorPagedResponse<RestaurantSettlementResponse>>> response =
                restaurantController.getSettlementsByCursor(1L, "c1", 25);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(page, response.getBody().getData());
    }

    @Test
    void enableBusyMode_returnsSuccess() {
        RestaurantBusyModeRequest request = new RestaurantBusyModeRequest();

        ResponseEntity<ApiResponse<Void>> response = restaurantController.enableBusyMode(1L, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Busy mode enabled", response.getBody().getMessage());
        verify(restaurantBusyService).setBusyMode(1L, request);
    }

    @Test
    void disableBusyMode_returnsSuccess() {
        ResponseEntity<ApiResponse<Void>> response = restaurantController.disableBusyMode(1L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Busy mode cleared", response.getBody().getMessage());
        verify(restaurantBusyService).clearBusyMode(1L);
    }

    @Test
    void getDashboard_returnsDashboard() {
        RestaurantDashboardResponse dashboard = RestaurantDashboardResponse.builder().build();
        when(restaurantDashboardService.getDashboard(1L, 30)).thenReturn(dashboard);

        ResponseEntity<ApiResponse<RestaurantDashboardResponse>> response =
                restaurantController.getDashboard(1L, 30);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(dashboard, response.getBody().getData());
    }
}
