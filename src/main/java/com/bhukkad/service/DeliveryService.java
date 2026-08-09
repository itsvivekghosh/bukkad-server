package com.bhukkad.service;

import com.bhukkad.dto.response.DeliveryAgentResponse;
import com.bhukkad.dto.response.OrderResponse;
import com.bhukkad.entity.DeliveryAgent;

import java.util.List;

public interface DeliveryService {
    DeliveryAgentResponse getProfile();
    DeliveryAgent getDeliveryAgentById(Long id);
    DeliveryAgent getCurrentDeliveryAgent();
    DeliveryAgent updateProfile(Long id, DeliveryAgent agent);

    // Availability
    void toggleAvailability(Boolean available);
    void updateLocation(Double latitude, Double longitude);

    // Assignments
    List<OrderResponse> getActiveDeliveries();
    List<OrderResponse> getDeliveryHistory();
    OrderResponse acceptDelivery(Long orderId);

    // Admin operations
    List<DeliveryAgentResponse> getAllDeliveryAgents();
    DeliveryAgent findNearestAvailableAgent(Double latitude, Double longitude);
}