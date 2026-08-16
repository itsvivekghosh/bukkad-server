package com.bhukkad.controller;

import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.service.AdminService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminControllerTest {

    @Mock
    private AdminService adminService;

    @InjectMocks
    private AdminController adminController;

    @Test
    void getDashboard_returnsStats() {
        Map<String, Object> stats = Map.of("users", 10);
        when(adminService.getDashboardStats()).thenReturn(stats);

        ResponseEntity<ApiResponse<Map<String, Object>>> response = adminController.getDashboard();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(stats, response.getBody().getData());
    }

    @Test
    void getAllUsers_passesFilters() {
        Map<String, Object> users = Map.of("total", 5);
        when(adminService.getAllUsers(1, 10, "CUSTOMER", "ada")).thenReturn(users);

        ResponseEntity<ApiResponse<Map<String, Object>>> response =
                adminController.getAllUsers(1, 10, "CUSTOMER", "ada");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(users, response.getBody().getData());
        verify(adminService).getAllUsers(1, 10, "CUSTOMER", "ada");
    }

    @Test
    void activateUser_returnsSuccess() {
        ResponseEntity<ApiResponse<Void>> response = adminController.activateUser(3L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("User activated", response.getBody().getMessage());
        verify(adminService).activateUser(3L);
    }

    @Test
    void deactivateUser_returnsSuccess() {
        ResponseEntity<ApiResponse<Void>> response = adminController.deactivateUser(3L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("User deactivated", response.getBody().getMessage());
        verify(adminService).deactivateUser(3L);
    }

    @Test
    void verifyOwner_returnsSuccess() {
        ResponseEntity<ApiResponse<Void>> response = adminController.verifyOwner(4L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Owner verified", response.getBody().getMessage());
        verify(adminService).verifyRestaurantOwner(4L);
    }

    @Test
    void verifyAgent_returnsSuccess() {
        ResponseEntity<ApiResponse<Void>> response = adminController.verifyAgent(8L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Agent verified", response.getBody().getMessage());
        verify(adminService).verifyDeliveryAgent(8L);
    }

    @Test
    void getAllOrders_returnsOrders() {
        Map<String, Object> orders = Map.of("total", 2);
        when(adminService.getAllOrders(0, 20, "PLACED")).thenReturn(orders);

        ResponseEntity<ApiResponse<Map<String, Object>>> response =
                adminController.getAllOrders(0, 20, "PLACED");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(orders, response.getBody().getData());
    }

    @Test
    void getAllRestaurants_returnsRestaurants() {
        Map<String, Object> restaurants = Map.of("total", 3);
        when(adminService.getAllRestaurants(0, 20, true)).thenReturn(restaurants);

        ResponseEntity<ApiResponse<Map<String, Object>>> response =
                adminController.getAllRestaurants(0, 20, true);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(restaurants, response.getBody().getData());
    }

    @Test
    void approveRestaurant_returnsSuccess() {
        ResponseEntity<ApiResponse<Void>> response = adminController.approveRestaurant(1L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Restaurant approved", response.getBody().getMessage());
        verify(adminService).approveRestaurant(1L);
    }

    @Test
    void suspendRestaurant_returnsSuccess() {
        ResponseEntity<ApiResponse<Void>> response = adminController.suspendRestaurant(1L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Restaurant suspended", response.getBody().getMessage());
        verify(adminService).suspendRestaurant(1L);
    }

    @Test
    void getRevenue_returnsStats() {
        Map<String, Object> revenue = Map.of("total", 1000);
        when(adminService.getRevenueStats(14)).thenReturn(revenue);

        ResponseEntity<ApiResponse<Map<String, Object>>> response = adminController.getRevenue(14);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(revenue, response.getBody().getData());
    }

    @Test
    void getAnalytics_returnsAnalytics() {
        Map<String, Object> analytics = Map.of("orders", 50);
        when(adminService.getAnalytics()).thenReturn(analytics);

        ResponseEntity<ApiResponse<Map<String, Object>>> response = adminController.getAnalytics();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(analytics, response.getBody().getData());
    }
}
