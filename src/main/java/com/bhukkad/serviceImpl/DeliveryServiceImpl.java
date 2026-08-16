package com.bhukkad.serviceImpl;

import com.bhukkad.cache.OrderCacheService;
import com.bhukkad.delivery.RiderDispatchService;
import com.bhukkad.dto.response.DeliveryAgentResponse;
import com.bhukkad.dto.response.OrderResponse;
import com.bhukkad.entity.DeliveryAgent;
import com.bhukkad.entity.Order;
import com.bhukkad.event.OrderEventPublisher;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.exception.UnauthorizedException;
import com.bhukkad.mapper.OrderMapper;
import com.bhukkad.repository.DeliveryAgentRepository;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.service.DeliveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DeliveryServiceImpl implements DeliveryService {

    private static final Set<Order.OrderStatus> ACTIVE_STATUSES = EnumSet.of(
            Order.OrderStatus.READY_FOR_PICKUP,
            Order.OrderStatus.OUT_FOR_DELIVERY
    );

    private final DeliveryAgentRepository deliveryAgentRepository;
    private final OrderRepository orderRepository;
    private final SecurityUtils securityUtils;
    private final OrderMapper orderMapper;
    private final OrderEventPublisher orderEventPublisher;
    private final OrderCacheService orderCacheService;
    private final RiderDispatchService riderDispatchService;

    @Override
    public DeliveryAgentResponse getProfile() {
        return mapToResponse(getCurrentDeliveryAgent());
    }

    @Override
    public DeliveryAgent getDeliveryAgentById(Long id) {
        return deliveryAgentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Agent not found"));
    }

    @Override
    public DeliveryAgent getCurrentDeliveryAgent() {
        Long agentId = securityUtils.getCurrentUserId();
        return deliveryAgentRepository.findById(agentId)
                .orElseThrow(() -> new ResourceNotFoundException("Agent not found"));
    }

    @Override
    @Transactional
    public DeliveryAgent updateProfile(Long id, DeliveryAgent updates) {
        DeliveryAgent agent = getOwnedAgent(id);

        if (StringUtils.hasText(updates.getFullName())) {
            agent.setFullName(updates.getFullName());
        }
        if (StringUtils.hasText(updates.getPhoneNumber())) {
            agent.setPhoneNumber(updates.getPhoneNumber());
        }
        if (StringUtils.hasText(updates.getVehicleType())) {
            agent.setVehicleType(updates.getVehicleType());
        }
        if (StringUtils.hasText(updates.getVehicleNumber())) {
            agent.setVehicleNumber(updates.getVehicleNumber());
        }
        if (StringUtils.hasText(updates.getLicenseNumber())) {
            agent.setLicenseNumber(updates.getLicenseNumber());
        }

        return deliveryAgentRepository.save(agent);
    }

    @Override
    @Transactional
    public void toggleAvailability(Boolean available) {
        if (available == null) {
            throw new BusinessException("Availability flag is required");
        }
        DeliveryAgent agent = getCurrentDeliveryAgent();
        agent.setAvailable(available);
        deliveryAgentRepository.save(agent);
    }

    @Override
    @Transactional
    public void updateLocation(Double latitude, Double longitude) {
        if (latitude == null || longitude == null) {
            throw new BusinessException("Latitude and longitude are required");
        }
        DeliveryAgent agent = getCurrentDeliveryAgent();
        agent.setCurrentLatitude(latitude);
        agent.setCurrentLongitude(longitude);
        deliveryAgentRepository.save(agent);
    }

    @Override
    public List<OrderResponse> getAvailableOrders() {
        DeliveryAgent agent = getCurrentDeliveryAgent();
        if (!Boolean.TRUE.equals(agent.getAvailable())) {
            return List.of();
        }
        return orderRepository.findAvailableDeliveriesForAgent(
                        agent.getId(), Order.OrderStatus.READY_FOR_PICKUP).stream()
                .map(orderMapper::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public List<OrderResponse> getActiveDeliveries() {
        DeliveryAgent agent = getCurrentDeliveryAgent();
        return orderRepository.findByDeliveryAgentIdAndStatusIn(agent.getId(), ACTIVE_STATUSES).stream()
                .map(orderMapper::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public List<OrderResponse> getDeliveryHistory() {
        DeliveryAgent agent = getCurrentDeliveryAgent();
        return orderRepository.findByDeliveryAgentIdAndStatus(agent.getId(), Order.OrderStatus.DELIVERED).stream()
                .map(orderMapper::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public OrderResponse acceptDelivery(Long orderId) {
        DeliveryAgent agent = getCurrentDeliveryAgent();
        if (!Boolean.TRUE.equals(agent.getAvailable())) {
            throw new BusinessException("Mark yourself available before accepting deliveries");
        }
        if (!Boolean.TRUE.equals(agent.getVerified())) {
            throw new BusinessException("Agent account is not verified");
        }

        Order order = orderRepository.findByIdWithDetails(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        if (order.getStatus() != Order.OrderStatus.READY_FOR_PICKUP) {
            throw new BusinessException("Order is not ready for pickup");
        }
        if (order.getDeliveryAgent() != null && !order.getDeliveryAgent().getId().equals(agent.getId())) {
            throw new BusinessException("Order is assigned to another agent");
        }

        order.setDeliveryAgent(agent);
        order = orderRepository.save(order);
        invalidateOrderCaches(order);
        orderEventPublisher.publishAgentAssigned(order);
        return orderMapper.toResponse(order);
    }

    @Override
    @Transactional
    public OrderResponse rejectDelivery(Long orderId) {
        DeliveryAgent agent = getCurrentDeliveryAgent();
        Order order = orderRepository.findByIdWithDetails(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        if (order.getDeliveryAgent() == null || !order.getDeliveryAgent().getId().equals(agent.getId())) {
            throw new BusinessException("Order is not assigned to you");
        }
        if (order.getStatus() == Order.OrderStatus.OUT_FOR_DELIVERY) {
            throw new BusinessException("Cannot reject an order already out for delivery");
        }

        order.setDeliveryAgent(null);
        order = orderRepository.save(order);
        invalidateOrderCaches(order);

        Optional<Order> reassigned = riderDispatchService.autoAssignNearestRider(order, Set.of(agent.getId()));
        if (reassigned.isPresent()) {
            order = reassigned.get();
        }
        return orderMapper.toResponse(order);
    }

    @Override
    public List<DeliveryAgentResponse> getAllDeliveryAgents() {
        return deliveryAgentRepository.findAll().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    public DeliveryAgent findNearestAvailableAgent(Double latitude, Double longitude) {
        return riderDispatchService.findNearestAvailableAgent(latitude, longitude, Set.of());
    }

    private DeliveryAgent getOwnedAgent(Long id) {
        DeliveryAgent agent = getDeliveryAgentById(id);
        if (!agent.getId().equals(securityUtils.getCurrentUserId())) {
            throw new UnauthorizedException("Cannot update another agent's profile");
        }
        return agent;
    }

    private void invalidateOrderCaches(Order order) {
        orderCacheService.invalidateOrder(
                order.getId(),
                order.getCustomer().getId(),
                order.getRestaurant().getId());
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
}
