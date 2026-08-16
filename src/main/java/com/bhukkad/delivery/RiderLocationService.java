package com.bhukkad.delivery;

import com.bhukkad.dto.request.RiderLocationRequest;
import com.bhukkad.dto.response.RiderLocationResponse;
import com.bhukkad.entity.DeliveryAgent;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.RiderLocationUpdate;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.DeliveryAgentRepository;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.repository.RiderLocationUpdateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Records rider GPS snapshots, syncs agent coordinates, and refreshes live ETA (V14).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RiderLocationService {

    private final RiderLocationUpdateRepository riderLocationUpdateRepository;
    private final OrderRepository orderRepository;
    private final DeliveryAgentRepository deliveryAgentRepository;
    private final OrderEtaService orderEtaService;

    /**
     * Records a rider location update, syncs agent GPS, and recalculates live ETA.
     */
    @Transactional
    public RiderLocationResponse recordLocation(Long orderId, RiderLocationRequest request) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        DeliveryAgent agent = order.getDeliveryAgent();
        if (agent == null) {
            throw new BusinessException("No delivery agent assigned to this order");
        }

        RiderLocationUpdate update = new RiderLocationUpdate();
        update.setOrder(order);
        update.setAgent(agent);
        update.setLatitude(request.getLatitude());
        update.setLongitude(request.getLongitude());
        update.setRecordedAt(LocalDateTime.now());
        riderLocationUpdateRepository.save(update);

        agent.setCurrentLatitude(request.getLatitude());
        agent.setCurrentLongitude(request.getLongitude());
        deliveryAgentRepository.save(agent);

        orderEtaService.applyLiveEta(order);
        orderRepository.save(order);

        return toResponse(update);
    }

    /** Returns the most recent rider location for an order. */
    public RiderLocationResponse getLatestForOrder(Long orderId) {
        RiderLocationUpdate update = riderLocationUpdateRepository
                .findFirstByOrderIdOrderByRecordedAtDesc(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("No rider location found for order"));
        return toResponse(update);
    }

    private RiderLocationResponse toResponse(RiderLocationUpdate update) {
        return RiderLocationResponse.builder()
                .orderId(update.getOrder().getId())
                .agentId(update.getAgent().getId())
                .latitude(update.getLatitude())
                .longitude(update.getLongitude())
                .recordedAt(update.getRecordedAt() != null ? update.getRecordedAt().toString() : null)
                .build();
    }
}
