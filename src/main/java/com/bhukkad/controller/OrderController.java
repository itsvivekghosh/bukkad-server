package com.bhukkad.controller;

import com.bhukkad.config.ApiPaths;

import com.bhukkad.dto.request.OrderRequest;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.CursorPagedResponse;
import com.bhukkad.dto.response.OrderCreateJobResponse;
import com.bhukkad.dto.response.OrderResponse;
import com.bhukkad.dto.response.OrderSummaryResponse;
import com.bhukkad.dto.response.PagedResponse;
import com.bhukkad.entity.Order;
import com.bhukkad.ratelimit.RateLimited;
import com.bhukkad.dto.response.ReorderResponse;
import com.bhukkad.order.AsyncOrderCreateService;
import com.bhukkad.order.OrderCreateJobService;
import com.bhukkad.service.CartService;
import com.bhukkad.service.OrderService;
import com.bhukkad.util.PaginationUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping(ApiPaths.V1_PREFIX + "/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final CartService cartService;
    private final AsyncOrderCreateService asyncOrderCreateService;
    private final OrderCreateJobService orderCreateJobService;

    // Customer endpoints
    @PostMapping("/customer/create")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<?>> createOrder(
            @Valid @RequestBody OrderRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestParam(defaultValue = "false") boolean async) {
        if (async) {
            String jobId = orderCreateJobService.createJob(idempotencyKey);
            asyncOrderCreateService.processOrderCreate(jobId, request, idempotencyKey);
            OrderCreateJobResponse job = orderCreateJobService.getJob(jobId);
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(ApiResponse.success("Order accepted for processing", job));
        }
        OrderResponse order = orderService.createOrder(request, idempotencyKey);
        return ResponseEntity.ok(ApiResponse.success("Order placed successfully", order));
    }

    @GetMapping("/customer/create/jobs/{jobId}")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<OrderCreateJobResponse>> getCreateOrderJob(@PathVariable String jobId) {
        return ResponseEntity.ok(ApiResponse.success(orderCreateJobService.getJob(jobId)));
    }

    @GetMapping("/customer/my-orders")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<PagedResponse<OrderSummaryResponse>>> getMyOrders(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PagedResponse<OrderSummaryResponse> orders = orderService.getCustomerOrders(page, size);
        return ResponseEntity.ok(ApiResponse.success(orders));
    }

    @GetMapping("/customer/my-orders/cursor")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<CursorPagedResponse<OrderSummaryResponse>>> getMyOrdersByCursor(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {
        CursorPagedResponse<OrderSummaryResponse> orders =
                orderService.getCustomerOrdersByCursor(cursor, size);
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
    @RateLimited("order-track")
    public ResponseEntity<ApiResponse<OrderResponse>> trackOrder(@PathVariable Long orderId) {
        OrderResponse order = orderService.trackOrder(orderId);
        return ResponseEntity.ok(ApiResponse.success(order));
    }

    @PostMapping("/customer/{orderId}/reorder")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<ReorderResponse>> reorder(
            @PathVariable Long orderId) {
        ReorderResponse response = cartService.reorderFromOrder(orderId);
        return ResponseEntity.ok(ApiResponse.success("Items added to cart", response));
    }

    @PutMapping("/customer/{orderId}/cancel")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<OrderResponse>> cancelOrder(
            @PathVariable Long orderId,
            @RequestParam String reason) {
        OrderResponse order = orderService.cancelOrder(orderId, reason);
        return ResponseEntity.ok(ApiResponse.success("Order cancelled successfully", order));
    }

    // Restaurant / cloud kitchen endpoints
    @GetMapping("/restaurant/{restaurantId}")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    public ResponseEntity<ApiResponse<PagedResponse<OrderSummaryResponse>>> getRestaurantOrders(
            @PathVariable Long restaurantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PagedResponse<OrderSummaryResponse> orders = orderService.getRestaurantOrders(restaurantId, page, size);
        return ResponseEntity.ok(ApiResponse.success(orders));
    }

    @GetMapping("/restaurant/{restaurantId}/cursor")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    public ResponseEntity<ApiResponse<CursorPagedResponse<OrderSummaryResponse>>> getRestaurantOrdersByCursor(
            @PathVariable Long restaurantId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {
        CursorPagedResponse<OrderSummaryResponse> orders =
                orderService.getRestaurantOrdersByCursor(restaurantId, cursor, size);
        return ResponseEntity.ok(ApiResponse.success(orders));
    }

    @GetMapping("/restaurant/{restaurantId}/pending")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    public ResponseEntity<ApiResponse<List<OrderSummaryResponse>>> getPendingOrders(
            @PathVariable Long restaurantId,
            @RequestParam(defaultValue = "" + PaginationUtils.KITCHEN_QUEUE_DEFAULT_LIMIT) int limit) {
        List<OrderSummaryResponse> orders = orderService.getPendingOrdersForRestaurant(restaurantId, limit);
        return ResponseEntity.ok(ApiResponse.success(orders));
    }

    @GetMapping("/restaurant/{restaurantId}/kitchen-queue")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    @RateLimited("kitchen-queue")
    public ResponseEntity<ApiResponse<List<OrderSummaryResponse>>> getKitchenQueue(
            @PathVariable Long restaurantId,
            @RequestParam(defaultValue = "" + PaginationUtils.KITCHEN_QUEUE_DEFAULT_LIMIT) int limit) {
        List<OrderSummaryResponse> orders = orderService.getKitchenActiveOrders(restaurantId, limit);
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
    public ResponseEntity<ApiResponse<PagedResponse<OrderSummaryResponse>>> getMyDeliveries(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PagedResponse<OrderSummaryResponse> orders = orderService.getDeliveryAgentOrders(null, page, size);
        return ResponseEntity.ok(ApiResponse.success(orders));
    }

    @GetMapping("/delivery/my-deliveries/cursor")
    @PreAuthorize("hasRole('DELIVERY_AGENT')")
    public ResponseEntity<ApiResponse<CursorPagedResponse<OrderSummaryResponse>>> getMyDeliveriesByCursor(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {
        CursorPagedResponse<OrderSummaryResponse> orders =
                orderService.getDeliveryAgentOrdersByCursor(null, cursor, size);
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
