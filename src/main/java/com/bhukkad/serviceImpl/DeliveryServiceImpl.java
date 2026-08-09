package com.bhukkad.serviceImpl;

import com.bhukkad.dto.response.DeliveryAgentResponse;
import com.bhukkad.dto.response.OrderResponse;
import com.bhukkad.entity.DeliveryAgent;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.DeliveryAgentRepository;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.service.DeliveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DeliveryServiceImpl implements DeliveryService {

    private final DeliveryAgentRepository deliveryAgentRepository;
    private final SecurityUtils securityUtils;

    @Override
    @Transactional(readOnly = true)
    public DeliveryAgentResponse getProfile() {
        Long agentId = securityUtils.getCurrentUserId();
        DeliveryAgent agent = deliveryAgentRepository.findById(agentId)
                .orElseThrow(() -> new ResourceNotFoundException("Agent not found"));

        return mapToResponse(agent);
    }

    private DeliveryAgentResponse mapToResponse(DeliveryAgent agent) {
        return DeliveryAgentResponse.builder()
                .id(agent.getId())
                .fullName(agent.getFullName())
                .email(agent.getEmail())
                .phoneNumber(agent.getPhoneNumber())
                .vehicleType(agent.getVehicleType())
                .vehicleNumber(agent.getVehicleNumber())
                .available(agent.getAvailable())
                .verified(agent.getVerified())
                .averageRating(agent.getAverageRating())
                .totalDeliveries(agent.getTotalDeliveries())
                .role(agent.getRole().name())
                .build();
    }

    // Add other missing interface methods with proper mapping...
    @Override public DeliveryAgent getDeliveryAgentById(Long id) { return null; }
    @Override public DeliveryAgent getCurrentDeliveryAgent() { return null; }
    @Override public DeliveryAgent updateProfile(Long id, DeliveryAgent agent) { return null; }
    @Override public void toggleAvailability(Boolean available) {}
    @Override public void updateLocation(Double lat, Double lon) {}
    @Override public List<OrderResponse> getActiveDeliveries() { return List.of(); }
    @Override public List<OrderResponse> getDeliveryHistory() { return List.of(); }
    @Override public OrderResponse acceptDelivery(Long orderId) { return null; }
    @Override public List<DeliveryAgentResponse> getAllDeliveryAgents() { return List.of(); }
    @Override public DeliveryAgent findNearestAvailableAgent(Double lat, Double lon) { return null; }
}