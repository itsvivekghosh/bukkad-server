package com.bhukkad.controller;

import com.bhukkad.dto.request.OrderRequest;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.OrderResponse;
import com.bhukkad.entity.Order;
import com.bhukkad.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    // Customer endpoints
    @PostMapping("/customer/create")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<OrderResponse>> createOrder(@Valid @RequestBody OrderRequest request) {
        OrderResponse order = orderService.createOrder(request);
        return ResponseEntity.ok(ApiResponse.success("Order placed successfully", order));
    }

    @GetMapping("/customer/my-orders")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<List<OrderResponse>>> getMyOrders() {
        List<OrderResponse> orders = orderService.getCustomerOrders();
        return ResponseEntity.ok(ApiResponse.success(orders));
    }

    @GetMapping("/customer/{orderId}")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<OrderResponse>> getOrderById(@PathVariable Long orderId) {
        OrderResponse order = orderService.getOrderById(orderId);
        return ResponseEntity.ok(ApiResponse.success(order));
    }

    @GetMapping("/customer/track/{orderId}")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<OrderResponse>> trackOrder(@PathVariable Long orderId) {
        OrderResponse order = orderService.trackOrder(orderId);
        return ResponseEntity.ok(ApiResponse.success(order));
    }

    @PutMapping("/customer/{orderId}/cancel")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<OrderResponse>> cancelOrder(
            @PathVariable Long orderId,
            @RequestParam String reason) {
        OrderResponse order = orderService.cancelOrder(orderId, reason);
        return ResponseEntity.ok(ApiResponse.success("Order cancelled successfully", order));
    }

    // Restaurant endpoints
    @GetMapping("/restaurant/{restaurantId}")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    public ResponseEntity<ApiResponse<List<OrderResponse>>> getRestaurantOrders(@PathVariable Long restaurantId) {
        List<OrderResponse> orders = orderService.getRestaurantOrders(restaurantId);
        return ResponseEntity.ok(ApiResponse.success(orders));
    }

    @GetMapping("/restaurant/{restaurantId}/pending")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    public ResponseEntity<ApiResponse<List<OrderResponse>>> getPendingOrders(@PathVariable Long restaurantId) {
        List<OrderResponse> orders = orderService.getPendingOrdersForRestaurant(restaurantId);
        return ResponseEntity.ok(ApiResponse.success(orders));
    }

    @PutMapping("/restaurant/{orderId}/accept")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    public ResponseEntity<ApiResponse<OrderResponse>> acceptOrder(@PathVariable Long orderId) {
        OrderResponse order = orderService.acceptOrder(orderId);
        return ResponseEntity.ok(ApiResponse.success("Order accepted", order));
    }

    @PutMapping("/restaurant/{orderId}/ready")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    public ResponseEntity<ApiResponse<OrderResponse>> markOrderReady(@PathVariable Long orderId) {
        OrderResponse order = orderService.markOrderReady(orderId);
        return ResponseEntity.ok(ApiResponse.success("Order marked as ready", order));
    }

    @PutMapping("/restaurant/{orderId}/assign-delivery")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    public ResponseEntity<ApiResponse<OrderResponse>> assignDeliveryAgent(
            @PathVariable Long orderId,
            @RequestParam Long agentId) {
        OrderResponse order = orderService.assignDeliveryAgent(orderId, agentId);
        return ResponseEntity.ok(ApiResponse.success("Delivery agent assigned", order));
    }

    // Delivery agent endpoints
    @GetMapping("/delivery/my-deliveries")
    @PreAuthorize("hasRole('DELIVERY_AGENT')")
    public ResponseEntity<ApiResponse<List<OrderResponse>>> getMyDeliveries() {
        Long agentId = null; // Get from security context
        List<OrderResponse> orders = orderService.getDeliveryAgentOrders(agentId);
        return ResponseEntity.ok(ApiResponse.success(orders));
    }

    @PutMapping("/delivery/{orderId}/picked-up")
    @PreAuthorize("hasRole('DELIVERY_AGENT')")
    public ResponseEntity<ApiResponse<OrderResponse>> markOrderPickedUp(@PathVariable Long orderId) {
        OrderResponse order = orderService.updateDeliveryStatus(orderId, Order.OrderStatus.OUT_FOR_DELIVERY);
        return ResponseEntity.ok(ApiResponse.success("Order picked up", order));
    }

    @PutMapping("/delivery/{orderId}/delivered")
    @PreAuthorize("hasRole('DELIVERY_AGENT')")
    public ResponseEntity<ApiResponse<OrderResponse>> markOrderDelivered(@PathVariable Long orderId) {
        OrderResponse order = orderService.markOrderDelivered(orderId);
        return ResponseEntity.ok(ApiResponse.success("Order delivered successfully", order));
    }

    // Common endpoint
    @GetMapping("/number/{orderNumber}")
    public ResponseEntity<ApiResponse<OrderResponse>> getOrderByNumber(@PathVariable String orderNumber) {
        OrderResponse order = orderService.getOrderByNumber(orderNumber);
        return ResponseEntity.ok(ApiResponse.success(order));
    }
}