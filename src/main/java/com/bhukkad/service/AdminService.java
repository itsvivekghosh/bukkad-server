package com.bhukkad.service;

import java.util.Map;

public interface AdminService {
    Map<String, Object> getDashboardStats();
    Map<String, Object> getAllUsers(int page, int size, String role, String search);
    void activateUser(Long userId);
    void deactivateUser(Long userId);
    void verifyRestaurantOwner(Long ownerId);
    void verifyDeliveryAgent(Long agentId);
    Map<String, Object> getAllOrders(int page, int size, String status);
    Map<String, Object> getAllRestaurants(int page, int size, Boolean active);
    void approveRestaurant(Long restaurantId);
    void suspendRestaurant(Long restaurantId);
    void setRestaurantCommission(Long restaurantId, Double commissionPercent);
    Map<String, Object> getRevenueStats(int days);
    Map<String, Object> getAnalytics();
}