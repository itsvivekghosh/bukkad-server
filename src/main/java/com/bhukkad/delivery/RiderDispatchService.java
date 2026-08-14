package com.bhukkad.delivery;

import com.bhukkad.cache.OrderCacheService;
import com.bhukkad.entity.Address;
import com.bhukkad.entity.DeliveryAgent;
import com.bhukkad.entity.Order;
import com.bhukkad.event.OrderEventPublisher;
import com.bhukkad.repository.DeliveryAgentRepository;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.util.DistanceCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class RiderDispatchService {

    private final DeliveryAgentRepository deliveryAgentRepository;
    private final OrderRepository orderRepository;
    private final OrderEventPublisher orderEventPublisher;
    private final OrderCacheService orderCacheService;

    @Transactional
    public Optional<Order> autoAssignNearestRider(Order order) {
        return autoAssignNearestRider(order, Set.of());
    }

    @Transactional
    public Optional<Order> autoAssignNearestRider(Order order, Set<Long> excludedAgentIds) {
        if (order == null
                || order.getStatus() != Order.OrderStatus.READY_FOR_PICKUP
                || order.getDeliveryAgent() != null) {
            return Optional.empty();
        }

        Address address = order.getDeliveryAddress();
        if (address == null || address.getLatitude() == null || address.getLongitude() == null) {
            log.debug("AUTO_DISPATCH_SKIPPED | orderId={} | reason=missing_delivery_coordinates", order.getId());
            return Optional.empty();
        }

        DeliveryAgent agent = findNearestAvailableAgent(
                address.getLatitude(),
                address.getLongitude(),
                excludedAgentIds);
        if (agent == null) {
            log.info("AUTO_DISPATCH_NO_AGENT | orderId={}", order.getId());
            return Optional.empty();
        }

        order.setDeliveryAgent(agent);
        order = orderRepository.save(order);
        invalidateOrderCaches(order);
        orderEventPublisher.publishAgentAssigned(order);
        log.info("AUTO_DISPATCH_ASSIGNED | orderId={} | agentId={}", order.getId(), agent.getId());
        return Optional.of(order);
    }

    public DeliveryAgent findNearestAvailableAgent(
            Double latitude,
            Double longitude,
            Set<Long> excludedAgentIds) {
        if (latitude == null || longitude == null) {
            return null;
        }

        return deliveryAgentRepository.findAvailableAgents().stream()
                .filter(agent -> excludedAgentIds == null || !excludedAgentIds.contains(agent.getId()))
                .filter(agent -> agent.getCurrentLatitude() != null && agent.getCurrentLongitude() != null)
                .filter(agent -> DistanceCalculator.isDeliveryPossible(
                        DistanceCalculator.calculateDistance(
                                latitude, longitude,
                                agent.getCurrentLatitude(), agent.getCurrentLongitude())))
                .min(Comparator.comparingDouble(agent -> DistanceCalculator.calculateDistance(
                        latitude, longitude,
                        agent.getCurrentLatitude(), agent.getCurrentLongitude())))
                .orElse(null);
    }

    private void invalidateOrderCaches(Order order) {
        orderCacheService.invalidateOrder(
                order.getId(),
                order.getCustomer().getId(),
                order.getRestaurant().getId());
    }
}
