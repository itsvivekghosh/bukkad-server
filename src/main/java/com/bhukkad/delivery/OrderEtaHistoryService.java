package com.bhukkad.delivery;

import com.bhukkad.config.DeliveryTruthProperties;
import com.bhukkad.dto.response.OrderEtaDetailResponse;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.OrderEtaSnapshot;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.OrderEtaSnapshotRepository;
import com.bhukkad.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Persists and retrieves ETA snapshot history for delivery-truth analytics (V14).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderEtaHistoryService {

    private final OrderEtaSnapshotRepository snapshotRepository;
    private final OrderRepository orderRepository;

    /**
     * Records an ETA snapshot from a computed result.
     */
    @Transactional
    public void recordSnapshot(Order order, OrderEtaService.EtaSnapshot snapshot,
                               double trafficFactor, double surgeMultiplier, String factorsSummary) {
        OrderEtaSnapshot entity = new OrderEtaSnapshot();
        entity.setOrder(order);
        entity.setEtaMinutes(snapshot.minutes());
        entity.setEtaAt(snapshot.etaAt());
        entity.setConfidenceLowMinutes(snapshot.confidenceLowMinutes());
        entity.setConfidenceHighMinutes(snapshot.confidenceHighMinutes());
        entity.setTrafficFactor(trafficFactor);
        entity.setSurgeMultiplier(surgeMultiplier);
        entity.setFactorsSummary(factorsSummary);
        snapshotRepository.save(entity);
    }

    /**
     * Returns detailed ETA with history for an order.
     */
    public OrderEtaDetailResponse getEtaDetail(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        List<OrderEtaSnapshot> history = snapshotRepository.findByOrderIdOrderByRecordedAtDesc(orderId);
        OrderEtaSnapshot latest = history.isEmpty() ? null : history.get(0);

        return OrderEtaDetailResponse.builder()
                .orderId(orderId)
                .etaMinutes(order.getLiveEtaMinutes())
                .etaAt(order.getLiveEtaAt() != null ? order.getLiveEtaAt().toString() : null)
                .confidenceLowMinutes(latest != null ? latest.getConfidenceLowMinutes() : null)
                .confidenceHighMinutes(latest != null ? latest.getConfidenceHighMinutes() : null)
                .trafficFactor(latest != null ? latest.getTrafficFactor() : null)
                .surgeMultiplier(latest != null ? latest.getSurgeMultiplier() : null)
                .factorsSummary(latest != null ? latest.getFactorsSummary() : null)
                .history(history.stream().limit(10).map(s -> OrderEtaDetailResponse.EtaHistoryEntry.builder()
                        .etaMinutes(s.getEtaMinutes())
                        .etaAt(s.getEtaAt() != null ? s.getEtaAt().toString() : null)
                        .recordedAt(s.getRecordedAt() != null ? s.getRecordedAt().toString() : null)
                        .build()).toList())
                .build();
    }
}
