package com.bhukkad.controller;

import com.bhukkad.dto.request.RestaurantRequest;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.RestaurantResponse;
import com.bhukkad.service.RestaurantService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RestaurantControllerTest {

    @Mock
    private RestaurantService restaurantService;

    @InjectMocks
    private RestaurantController restaurantController;

    @Test
    void getAllRestaurants_returnsActiveRestaurants() {
        List<RestaurantResponse> restaurants = List.of(new RestaurantResponse());
        when(restaurantService.getAllActiveRestaurants()).thenReturn(restaurants);

        ResponseEntity<ApiResponse<List<RestaurantResponse>>> response = restaurantController.getAllRestaurants();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(restaurants, response.getBody().getData());
        verify(restaurantService).getAllActiveRestaurants();
    }

    @Test
    void getRestaurantById_returnsRestaurant() {
        RestaurantResponse restaurant = new RestaurantResponse();
        when(restaurantService.getRestaurantById(1L)).thenReturn(restaurant);

        ResponseEntity<ApiResponse<RestaurantResponse>> response = restaurantController.getRestaurantById(1L);

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
}
