package com.bhukkad.controller;

import com.bhukkad.dto.request.RestaurantRequest;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.RestaurantResponse;
import com.bhukkad.service.RestaurantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/restaurants")
@RequiredArgsConstructor
public class RestaurantController {

    private final RestaurantService restaurantService;

    // Public endpoints
    @GetMapping("/public")
    public ResponseEntity<ApiResponse<List<RestaurantResponse>>> getAllRestaurants() {
        List<RestaurantResponse> restaurants = restaurantService.getAllActiveRestaurants();
        return ResponseEntity.ok(ApiResponse.success(restaurants));
    }

    @GetMapping("/public/{id}")
    public ResponseEntity<ApiResponse<RestaurantResponse>> getRestaurantById(@PathVariable Long id) {
        RestaurantResponse restaurant = restaurantService.getRestaurantById(id);
        return ResponseEntity.ok(ApiResponse.success(restaurant));
    }

    @GetMapping("/public/search")
    public ResponseEntity<ApiResponse<List<RestaurantResponse>>> searchRestaurants(@RequestParam String keyword) {
        List<RestaurantResponse> restaurants = restaurantService.searchRestaurants(keyword);
        return ResponseEntity.ok(ApiResponse.success(restaurants));
    }

    @GetMapping("/public/filter")
    public ResponseEntity<ApiResponse<List<RestaurantResponse>>> filterRestaurants(
            @RequestParam(required = false) Long cuisineId,
            @RequestParam(required = false) Boolean isPureVeg) {
        List<RestaurantResponse> restaurants = restaurantService.filterRestaurants(cuisineId, isPureVeg);
        return ResponseEntity.ok(ApiResponse.success(restaurants));
    }

    // Owner endpoints
    @PostMapping("/owner")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    public ResponseEntity<ApiResponse<RestaurantResponse>> createRestaurant(
            @Valid @RequestBody RestaurantRequest request) {
        RestaurantResponse restaurant = restaurantService.createRestaurant(request);
        return ResponseEntity.ok(ApiResponse.success("Restaurant created successfully", restaurant));
    }

    @GetMapping("/owner/my-restaurants")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    public ResponseEntity<ApiResponse<List<RestaurantResponse>>> getMyRestaurants() {
        List<RestaurantResponse> restaurants = restaurantService.getMyRestaurants();
        return ResponseEntity.ok(ApiResponse.success(restaurants));
    }

    @PutMapping("/owner/{id}")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    public ResponseEntity<ApiResponse<RestaurantResponse>> updateRestaurant(
            @PathVariable Long id,
            @Valid @RequestBody RestaurantRequest request) {
        RestaurantResponse restaurant = restaurantService.updateRestaurant(id, request);
        return ResponseEntity.ok(ApiResponse.success("Restaurant updated successfully", restaurant));
    }

    @DeleteMapping("/owner/{id}")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    public ResponseEntity<ApiResponse<Void>> deleteRestaurant(@PathVariable Long id) {
        restaurantService.deleteRestaurant(id);
        return ResponseEntity.ok(ApiResponse.success("Restaurant deleted successfully", null));
    }

    @PutMapping("/owner/{id}/toggle-status")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    public ResponseEntity<ApiResponse<Void>> toggleRestaurantStatus(
            @PathVariable Long id,
            @RequestParam Boolean isOpen) {
        restaurantService.toggleRestaurantStatus(id, isOpen);
        return ResponseEntity.ok(ApiResponse.success("Restaurant status updated", null));
    }
}