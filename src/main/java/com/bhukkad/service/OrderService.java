package com.bhukkad.service;

import com.bhukkad.dto.request.BatchOrderRequest;
import com.bhukkad.dto.request.OrderRequest;
import com.bhukkad.dto.response.BatchOrderResponse;
import com.bhukkad.dto.response.CursorPagedResponse;
import com.bhukkad.dto.response.OrderResponse;
import com.bhukkad.dto.response.OrderSummaryResponse;
import com.bhukkad.dto.response.PagedResponse;
import com.bhukkad.entity.Order;

import java.util.List;

public interface OrderService {
    OrderResponse createOrder(OrderRequest request, String idempotencyKey);
    BatchOrderResponse createBatchOrders(BatchOrderRequest request, String idempotencyKey);
    OrderResponse getOrderById(Long id);
    OrderResponse getOrderByNumber(String orderNumber);
    PagedResponse<OrderSummaryResponse> getCustomerOrders(int page, int size);
    CursorPagedResponse<OrderSummaryResponse> getCustomerOrdersByCursor(String cursor, int size);
    PagedResponse<OrderSummaryResponse> getRestaurantOrders(Long restaurantId, int page, int size);
    CursorPagedResponse<OrderSummaryResponse> getRestaurantOrdersByCursor(Long restaurantId, String cursor, int size);
    PagedResponse<OrderSummaryResponse> getDeliveryAgentOrders(Long agentId, int page, int size);
    CursorPagedResponse<OrderSummaryResponse> getDeliveryAgentOrdersByCursor(Long agentId, String cursor, int size);

    // Order status management
    OrderResponse updateOrderStatus(Long orderId, Order.OrderStatus status);
    OrderResponse confirmOrder(Long orderId);
    OrderResponse cancelOrder(Long orderId, String reason);

    // Restaurant / cloud kitchen operations
    List<OrderSummaryResponse> getPendingOrdersForRestaurant(Long restaurantId, int limit);
    List<OrderSummaryResponse> getKitchenActiveOrders(Long restaurantId, int limit);
    OrderResponse acceptOrder(Long orderId);
    OrderResponse markOrderReady(Long orderId);

    // Delivery operations
    OrderResponse assignDeliveryAgent(Long orderId, Long agentId);
    OrderResponse updateDeliveryStatus(Long orderId, Order.OrderStatus status);
    OrderResponse markOrderDelivered(Long orderId);

    // Tracking
    OrderResponse trackOrder(Long orderId);
}
