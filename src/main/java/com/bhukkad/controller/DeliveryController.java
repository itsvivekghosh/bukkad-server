package com.bhukkad.controller;

import com.bhukkad.config.ApiPaths;

import com.bhukkad.dto.request.RiderLocationRequest;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.DeliveryAgentResponse;
import com.bhukkad.dto.response.OrderResponse;
import com.bhukkad.dto.response.RiderBatchResponse;
import com.bhukkad.dto.response.RiderLocationResponse;
import com.bhukkad.delivery.RiderBatchDispatchService;
import com.bhukkad.delivery.RiderLocationService;
import com.bhukkad.entity.DeliveryAgent;
import com.bhukkad.service.DeliveryService;
import com.bhukkad.service.RiderPayoutService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping(ApiPaths.V1_PREFIX + "/delivery")
@RequiredArgsConstructor
@PreAuthorize("hasRole('DELIVERY_AGENT')")
public class DeliveryController {

    private final DeliveryService deliveryService;
    private final RiderPayoutService riderPayoutService;
    private final RiderLocationService riderLocationService;
    private final RiderBatchDispatchService riderBatchDispatchService;

    @GetMapping("/earnings/summary")
    public ResponseEntity<ApiResponse<com.bhukkad.dto.response.RiderEarningsSummaryResponse>> getEarningsSummary() {
        return ResponseEntity.ok(ApiResponse.success(riderPayoutService.getEarningsSummary()));
    }

    @GetMapping("/earnings")
    public ResponseEntity<ApiResponse<com.bhukkad.dto.response.PagedResponse<com.bhukkad.dto.response.RiderPayoutResponse>>> getEarningsHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(riderPayoutService.getPayoutHistory(page, size)));
    }

    @GetMapping("/profile")
    public ResponseEntity<ApiResponse<DeliveryAgentResponse>> getProfile() {
        return ResponseEntity.ok(ApiResponse.success(deliveryService.getProfile()));
    }

    @PutMapping("/profile")
    public ResponseEntity<ApiResponse<DeliveryAgentResponse>> updateProfile(@RequestBody DeliveryAgent agent) {
        DeliveryAgentResponse updatedAgent = deliveryService.updateProfile(
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

    @GetMapping("/available-orders")
    public ResponseEntity<ApiResponse<List<OrderResponse>>> getAvailableOrders() {
        List<OrderResponse> orders = deliveryService.getAvailableOrders();
        return ResponseEntity.ok(ApiResponse.success(orders));
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

    @PostMapping("/{orderId}/reject")
    public ResponseEntity<ApiResponse<OrderResponse>> rejectDelivery(@PathVariable Long orderId) {
        OrderResponse order = deliveryService.rejectDelivery(orderId);
        return ResponseEntity.ok(ApiResponse.success("Delivery rejected", order));
    }

    /** Records GPS coordinates for an active delivery (live map tracking). */
    @PostMapping("/orders/{orderId}/location")
    public ResponseEntity<ApiResponse<RiderLocationResponse>> updateOrderLocation(
            @PathVariable Long orderId,
            @RequestBody RiderLocationRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Location recorded", riderLocationService.recordLocation(orderId, request)));
    }

    /** Creates a multi-stop delivery batch from active orders (V16). */
    @PostMapping("/batches")
    public ResponseEntity<ApiResponse<RiderBatchResponse>> createDeliveryBatch() {
        return ResponseEntity.ok(ApiResponse.success(
                "Delivery batch created", riderBatchDispatchService.createBatchFromActiveOrders()));
    }

    @GetMapping("/batches/active")
    public ResponseEntity<ApiResponse<RiderBatchResponse>> getActiveBatch() {
        return ResponseEntity.ok(ApiResponse.success(riderBatchDispatchService.getActiveBatch()));
    }

    @PutMapping("/batches/{batchId}/complete")
    public ResponseEntity<ApiResponse<RiderBatchResponse>> completeBatch(@PathVariable Long batchId) {
        return ResponseEntity.ok(ApiResponse.success(
                "Batch completed", riderBatchDispatchService.completeBatch(batchId)));
    }
}
