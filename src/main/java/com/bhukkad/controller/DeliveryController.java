package com.bhukkad.controller;

import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.OrderResponse;
import com.bhukkad.entity.DeliveryAgent;
import com.bhukkad.service.DeliveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/delivery")
@RequiredArgsConstructor
@PreAuthorize("hasRole('DELIVERY_AGENT')")
public class DeliveryController {

    private final DeliveryService deliveryService;

    @GetMapping("/profile")
    public ResponseEntity<ApiResponse<DeliveryAgent>> getProfile() {
        DeliveryAgent agent = deliveryService.getCurrentDeliveryAgent();
        return ResponseEntity.ok(ApiResponse.success(agent));
    }

    @PutMapping("/profile")
    public ResponseEntity<ApiResponse<DeliveryAgent>> updateProfile(@RequestBody DeliveryAgent agent) {
        DeliveryAgent updatedAgent = deliveryService.updateProfile(
                deliveryService.getCurrentDeliveryAgent().getId(), agent);
        return ResponseEntity.ok(ApiResponse.success("Profile updated successfully", updatedAgent));
    }

    @PutMapping("/toggle-availability")
    public ResponseEntity<ApiResponse<Void>> toggleAvailability(@RequestParam Boolean available) {
        deliveryService.toggleAvailability(available);
        return ResponseEntity.ok(ApiResponse.success("Availability updated", null));
    }

    @PutMapping("/update-location")
    public ResponseEntity<ApiResponse<Void>> updateLocation(
            @RequestParam Double latitude,
            @RequestParam Double longitude) {
        deliveryService.updateLocation(latitude, longitude);
        return ResponseEntity.ok(ApiResponse.success("Location updated", null));
    }

    @GetMapping("/active-deliveries")
    public ResponseEntity<ApiResponse<List<OrderResponse>>> getActiveDeliveries() {
        List<OrderResponse> deliveries = deliveryService.getActiveDeliveries();
        return ResponseEntity.ok(ApiResponse.success(deliveries));
    }

    @GetMapping("/delivery-history")
    public ResponseEntity<ApiResponse<List<OrderResponse>>> getDeliveryHistory() {
        List<OrderResponse> history = deliveryService.getDeliveryHistory();
        return ResponseEntity.ok(ApiResponse.success(history));
    }

    @PostMapping("/{orderId}/accept")
    public ResponseEntity<ApiResponse<OrderResponse>> acceptDelivery(@PathVariable Long orderId) {
        OrderResponse order = deliveryService.acceptDelivery(orderId);
        return ResponseEntity.ok(ApiResponse.success("Delivery accepted", order));
    }
}