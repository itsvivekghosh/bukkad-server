package com.bhukkad.service;

import com.bhukkad.dto.request.OrderRequest;
import com.bhukkad.dto.response.OrderResponse;
import com.bhukkad.entity.Order;

import java.util.List;

public interface OrderService {
    OrderResponse createOrder(OrderRequest request);
    OrderResponse getOrderById(Long id);
    OrderResponse getOrderByNumber(String orderNumber);
    List<OrderResponse> getCustomerOrders();
    List<OrderResponse> getRestaurantOrders(Long restaurantId);
    List<OrderResponse> getDeliveryAgentOrders(Long agentId);

    // Order status management
    OrderResponse updateOrderStatus(Long orderId, Order.OrderStatus status);
    OrderResponse confirmOrder(Long orderId);
    OrderResponse cancelOrder(Long orderId, String reason);

    // Restaurant operations
    List<OrderResponse> getPendingOrdersForRestaurant(Long restaurantId);
    OrderResponse acceptOrder(Long orderId);
    OrderResponse markOrderReady(Long orderId);

    // Delivery operations
    OrderResponse assignDeliveryAgent(Long orderId, Long agentId);
    OrderResponse updateDeliveryStatus(Long orderId, Order.OrderStatus status);
    OrderResponse markOrderDelivered(Long orderId);

    // Tracking
    OrderResponse trackOrder(Long orderId);
}