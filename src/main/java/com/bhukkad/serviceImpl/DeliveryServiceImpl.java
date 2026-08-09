package com.bhukkad.serviceImpl;

import com.bhukkad.dto.response.DeliveryAgentResponse;
import com.bhukkad.dto.response.OrderResponse;
import com.bhukkad.entity.DeliveryAgent;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.exception.UnauthorizedException;
import com.bhukkad.repository.DeliveryAgentRepository;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.service.DeliveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DeliveryServiceImpl implements DeliveryService {

    private final DeliveryAgentRepository deliveryAgentRepository;
    private final OrderRepository orderRepository;
    private final SecurityUtils securityUtils;

    @Override
    public DeliveryAgent getDeliveryAgentById(Long id) {
        return deliveryAgentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Delivery agent not found"));
    }

    @Override
    public DeliveryAgent getCurrentDeliveryAgent() {
        Long agentId = securityUtils.getCurrentUserId();
        return getDeliveryAgentById(agentId);
    }

    @Override
    @Transactional
    public DeliveryAgent updateProfile(Long id, DeliveryAgent agent) {
        if (!securityUtils.isCurrentUser(id)) {
            throw new UnauthorizedException("Cannot update another agent's profile");
        }

        DeliveryAgent existingAgent = getDeliveryAgentById(id);
        existingAgent.setFullName(agent.getFullName());
        existingAgent.setPhoneNumber(agent.getPhoneNumber());
        existingAgent.setVehicleType(agent.getVehicleType());
        existingAgent.setVehicleNumber(agent.getVehicleNumber());
        existingAgent.setLicenseNumber(agent.getLicenseNumber());
        existingAgent.setProfileImageUrl(agent.getProfileImageUrl());

        return deliveryAgentRepository.save(existingAgent);
    }

    @Override
    @Transactional
    public void toggleAvailability(Boolean available) {
        DeliveryAgent agent = getCurrentDeliveryAgent();
        agent.setAvailable(available);
        deliveryAgentRepository.save(agent);
    }

    @Override
    @Transactional
    public void updateLocation(Double latitude, Double longitude) {
        DeliveryAgent agent = getCurrentDeliveryAgent();
        agent.setCurrentLatitude(latitude);
        agent.setCurrentLongitude(longitude);
        deliveryAgentRepository.save(agent);
    }

    @Override
    public List<OrderResponse> getActiveDeliveries() {
        Long agentId = securityUtils.getCurrentUserId();
        return orderRepository.findActiveDeliveriesByAgent(agentId).stream()
                .map(order -> OrderResponse.builder()
                        .id(order.getId())
                        .orderNumber(order.getOrderNumber())
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    public List<OrderResponse> getDeliveryHistory() {
        Long agentId = securityUtils.getCurrentUserId();
        return orderRepository.findByDeliveryAgentIdOrderByCreatedAtDesc(agentId).stream()
                .map(order -> OrderResponse.builder()
                        .id(order.getId())
                        .orderNumber(order.getOrderNumber())
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public OrderResponse acceptDelivery(Long orderId) {
        // Implementation for accepting delivery
        return null;
    }

    @Override
    public List<DeliveryAgentResponse> getAllDeliveryAgents() {
        return deliveryAgentRepository.findAll().stream()
                .map(this::mapToDeliveryAgentResponse)
                .collect(Collectors.toList());
    }

    @Override
    public DeliveryAgent findNearestAvailableAgent(Double latitude, Double longitude) {
        List<DeliveryAgent> availableAgents = deliveryAgentRepository.findAvailableAgents();

        // Simple implementation - in real scenario, use geospatial queries
        return availableAgents.stream()
                .findFirst()
                .orElse(null);
    }

    private DeliveryAgentResponse mapToDeliveryAgentResponse(DeliveryAgent agent) {
        return DeliveryAgentResponse.builder()
                .id(agent.getId())
                .fullName(agent.getFullName())
                .phoneNumber(agent.getPhoneNumber())
                .vehicleType(agent.getVehicleType())
                .vehicleNumber(agent.getVehicleNumber())
                .available(agent.getAvailable())
                .averageRating(agent.getAverageRating())
                .totalDeliveries(agent.getTotalDeliveries())
                .currentLatitude(agent.getCurrentLatitude())
                .currentLongitude(agent.getCurrentLongitude())
                .build();
    }
}