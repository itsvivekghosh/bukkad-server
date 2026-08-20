package com.bhukkad.controller;

import com.bhukkad.config.ApiPaths;

import com.bhukkad.dto.request.OnboardingReviewRequest;
import com.bhukkad.dto.request.TestNotificationRequest;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.service.AdminService;
import com.bhukkad.service.NotificationService;
import com.bhukkad.service.RestaurantService;
import com.bhukkad.service.RiderPayoutService;
import com.bhukkad.settlement.RestaurantSettlementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping(ApiPaths.V1_PREFIX + "/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final AdminService adminService;
    private final RiderPayoutService riderPayoutService;
    private final RestaurantSettlementService restaurantSettlementService;
    private final NotificationService notificationService;
    private final RestaurantService restaurantService;

    @GetMapping("/dashboard")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDashboard() {
        return ResponseEntity.ok(ApiResponse.success(adminService.getDashboardStats()));
    }

    @GetMapping("/users")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getAllUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String search) {
        return ResponseEntity.ok(ApiResponse.success(adminService.getAllUsers(page, size, role, search)));
    }

    @PutMapping("/users/{userId}/activate")
    public ResponseEntity<ApiResponse<Void>> activateUser(@PathVariable Long userId) {
        adminService.activateUser(userId);
        return ResponseEntity.ok(ApiResponse.success("User activated", null));
    }

    @PutMapping("/users/{userId}/deactivate")
    public ResponseEntity<ApiResponse<Void>> deactivateUser(@PathVariable Long userId) {
        adminService.deactivateUser(userId);
        return ResponseEntity.ok(ApiResponse.success("User deactivated", null));
    }

    @PutMapping("/owners/{ownerId}/verify")
    public ResponseEntity<ApiResponse<Void>> verifyOwner(@PathVariable Long ownerId) {
        adminService.verifyRestaurantOwner(ownerId);
        return ResponseEntity.ok(ApiResponse.success("Owner verified", null));
    }

    @PutMapping("/agents/{agentId}/verify")
    public ResponseEntity<ApiResponse<Void>> verifyAgent(@PathVariable Long agentId) {
        adminService.verifyDeliveryAgent(agentId);
        return ResponseEntity.ok(ApiResponse.success("Agent verified", null));
    }

    @GetMapping("/orders")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getAllOrders(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(ApiResponse.success(adminService.getAllOrders(page, size, status)));
    }

    @GetMapping("/restaurants")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getAllRestaurants(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Boolean active) {
        return ResponseEntity.ok(ApiResponse.success(adminService.getAllRestaurants(page, size, active)));
    }

    @PutMapping("/restaurants/{restaurantId}/approve")
    public ResponseEntity<ApiResponse<Void>> approveRestaurant(@PathVariable Long restaurantId) {
        adminService.approveRestaurant(restaurantId);
        return ResponseEntity.ok(ApiResponse.success("Restaurant approved", null));
    }

    @PutMapping("/restaurants/{restaurantId}/suspend")
    public ResponseEntity<ApiResponse<Void>> suspendRestaurant(@PathVariable Long restaurantId) {
        adminService.suspendRestaurant(restaurantId);
        return ResponseEntity.ok(ApiResponse.success("Restaurant suspended", null));
    }

    /** Approves or rejects a dark kitchen onboarding application. */
    @PutMapping("/restaurants/{restaurantId}/onboarding")
    public ResponseEntity<ApiResponse<Void>> reviewOnboarding(
            @PathVariable Long restaurantId, @Valid @RequestBody OnboardingReviewRequest request) {
        restaurantService.reviewOnboarding(restaurantId, request.getApproved(), request.getReason());
        String message = Boolean.TRUE.equals(request.getApproved())
                ? "Onboarding approved" : "Onboarding rejected";
        return ResponseEntity.ok(ApiResponse.success(message, null));
    }

    @GetMapping("/revenue")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getRevenue(
            @RequestParam(defaultValue = "7") int days) {
        return ResponseEntity.ok(ApiResponse.success(adminService.getRevenueStats(days)));
    }

    @GetMapping("/analytics")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getAnalytics() {
        return ResponseEntity.ok(ApiResponse.success(adminService.getAnalytics()));
    }

    @PutMapping("/agents/{agentId}/settle-payouts")
    public ResponseEntity<ApiResponse<Map<String, Object>>> settleAgentPayouts(@PathVariable Long agentId) {
        int settled = riderPayoutService.settlePendingPayouts(agentId);
        return ResponseEntity.ok(ApiResponse.success(
                "Rider payouts settled",
                Map.of("agentId", agentId, "settledCount", settled)));
    }

    @PutMapping("/restaurants/{restaurantId}/settle-payouts")
    public ResponseEntity<ApiResponse<Map<String, Object>>> settleRestaurantPayouts(
            @PathVariable Long restaurantId) {
        int settled = restaurantSettlementService.settlePendingForRestaurant(restaurantId);
        return ResponseEntity.ok(ApiResponse.success(
                "Restaurant settlements completed",
                Map.of("restaurantId", restaurantId, "settledCount", settled)));
    }

    @PutMapping("/restaurants/{restaurantId}/commission")
    public ResponseEntity<ApiResponse<Map<String, Object>>> setRestaurantCommission(
            @PathVariable Long restaurantId,
            @RequestParam Double percent) {
        adminService.setRestaurantCommission(restaurantId, percent);
        return ResponseEntity.ok(ApiResponse.success(
                "Restaurant commission updated",
                Map.of("restaurantId", restaurantId, "commissionPercent", percent)));
    }

    @PostMapping("/notifications/test")
    public ResponseEntity<ApiResponse<Map<String, Object>>> sendTestNotification(
            @RequestBody TestNotificationRequest request) {
        notificationService.sendTestNotification(
                request.getChannel(),
                request.getRecipient(),
                request.getMessage());
        return ResponseEntity.ok(ApiResponse.success(
                "Test notification dispatched",
                Map.of("channel", request.getChannel(), "recipient", request.getRecipient())));
    }
}