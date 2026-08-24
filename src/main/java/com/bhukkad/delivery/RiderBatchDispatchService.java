package com.bhukkad.delivery;

import com.bhukkad.dto.response.RiderBatchResponse;
import com.bhukkad.entity.DeliveryAgent;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.RiderDeliveryBatch;
import com.bhukkad.entity.RiderDeliveryBatchOrder;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.repository.RiderDeliveryBatchOrderRepository;
import com.bhukkad.repository.RiderDeliveryBatchRepository;
import com.bhukkad.service.DeliveryService;
import com.bhukkad.util.DistanceCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Groups nearby ready orders into multi-stop delivery batches for riders (V16).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RiderBatchDispatchService {

    private static final int MAX_BATCH_SIZE = 3;
    private static final double MAX_BATCH_RADIUS_KM = 2.0;

    private final RiderDeliveryBatchRepository batchRepository;
    private final RiderDeliveryBatchOrderRepository batchOrderRepository;
    private final OrderRepository orderRepository;
    private final DeliveryService deliveryService;

    /**
     * Creates a delivery batch from the agent's active ready orders within proximity.
     */
    @Transactional
    public RiderBatchResponse createBatchFromActiveOrders() {
        DeliveryAgent agent = deliveryService.getCurrentDeliveryAgent();
        List<Order> activeOrders = orderRepository.findByDeliveryAgentIdAndStatusIn(
                agent.getId(),
                List.of(Order.OrderStatus.READY_FOR_PICKUP, Order.OrderStatus.OUT_FOR_DELIVERY));

        if (activeOrders.isEmpty()) {
            throw new BusinessException("No active orders available for batching");
        }

        batchRepository.findFirstByAgentIdAndStatusOrderByCreatedAtDesc(
                agent.getId(), RiderDeliveryBatch.BatchStatus.ACTIVE)
                .ifPresent(existing -> {
                    throw new BusinessException("Agent already has an active delivery batch");
                });

        List<Order> batchOrders = selectNearbyOrders(activeOrders, MAX_BATCH_SIZE);
        RiderDeliveryBatch batch = new RiderDeliveryBatch();
        batch.setAgent(agent);
        batch.setStatus(RiderDeliveryBatch.BatchStatus.ACTIVE);
        batch = batchRepository.save(batch);

        int sequence = 1;
        for (Order order : batchOrders) {
            RiderDeliveryBatchOrder batchOrder = new RiderDeliveryBatchOrder();
            batchOrder.setBatchId(batch.getId());
            batchOrder.setOrderId(order.getId());
            batchOrder.setSequenceNumber(sequence++);
            batchOrderRepository.save(batchOrder);
        }

        return toResponse(batch, batchOrders);
    }

    /** Returns the agent's current active batch, if any. */
    public RiderBatchResponse getActiveBatch() {
        DeliveryAgent agent = deliveryService.getCurrentDeliveryAgent();
        RiderDeliveryBatch batch = batchRepository
                .findFirstByAgentIdAndStatusOrderByCreatedAtDesc(agent.getId(), RiderDeliveryBatch.BatchStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("No active delivery batch"));
        List<RiderDeliveryBatchOrder> entries = batchOrderRepository.findByBatchIdOrderBySequenceNumberAsc(batch.getId());
        List<Order> orders = fetchOrdersByIds(entries);
        return toResponse(batch, orders);
    }

    /** Marks a batch as completed when all stops are delivered. */
    @Transactional
    public RiderBatchResponse completeBatch(Long batchId) {
        RiderDeliveryBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("Delivery batch not found"));
        batch.setStatus(RiderDeliveryBatch.BatchStatus.COMPLETED);
        batch.setCompletedAt(LocalDateTime.now());
        batchRepository.save(batch);
        List<RiderDeliveryBatchOrder> entries = batchOrderRepository.findByBatchIdOrderBySequenceNumberAsc(batchId);
        List<Order> orders = fetchOrdersByIds(entries);
        return toResponse(batch, orders);
    }

    /**
     * Loads all orders for batch entries in a single IN query instead of one
     * {@code findById} per entry (N+1), preserving the entry order.
     */
    private List<Order> fetchOrdersByIds(List<RiderDeliveryBatchOrder> entries) {
        if (entries.isEmpty()) {
            return List.of();
        }
        List<Long> ids = entries.stream().map(RiderDeliveryBatchOrder::getOrderId).toList();
        Map<Long, Order> byId = orderRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Order::getId, o -> o));
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    private List<Order> selectNearbyOrders(List<Order> orders, int maxSize) {
        if (orders.size() <= maxSize) return orders;
        Order anchor = orders.get(0);
        double anchorLat = anchor.getRestaurant().getAddress().getLatitude();
        double anchorLng = anchor.getRestaurant().getAddress().getLongitude();

        List<Order> sorted = new ArrayList<>(orders);
        sorted.sort(Comparator.comparingDouble(o -> distanceToRestaurant(o, anchorLat, anchorLng)));

        List<Order> selected = new ArrayList<>();
        selected.add(sorted.get(0));
        for (int i = 1; i < sorted.size() && selected.size() < maxSize; i++) {
            Order candidate = sorted.get(i);
            if (distanceToRestaurant(candidate, anchorLat, anchorLng) <= MAX_BATCH_RADIUS_KM) {
                selected.add(candidate);
            }
        }
        return selected;
    }

    private double distanceToRestaurant(Order order, double lat, double lng) {
        return DistanceCalculator.calculateDistance(
                lat, lng,
                order.getRestaurant().getAddress().getLatitude(),
                order.getRestaurant().getAddress().getLongitude());
    }

    private RiderBatchResponse toResponse(RiderDeliveryBatch batch, List<Order> orders) {
        return RiderBatchResponse.builder()
                .batchId(batch.getId())
                .agentId(batch.getAgent().getId())
                .status(batch.getStatus().name())
                .createdAt(batch.getCreatedAt() != null ? batch.getCreatedAt().toString() : null)
                .completedAt(batch.getCompletedAt() != null ? batch.getCompletedAt().toString() : null)
                .orders(orders.stream().map(o -> RiderBatchResponse.BatchOrderEntry.builder()
                        .orderId(o.getId())
                        .orderNumber(o.getOrderNumber())
                        .status(o.getStatus().name())
                        .sequenceNumber(batchOrderRepository.findByBatchIdOrderBySequenceNumberAsc(batch.getId()).stream()
                                .filter(bo -> bo.getOrderId().equals(o.getId()))
                                .map(RiderDeliveryBatchOrder::getSequenceNumber)
                                .findFirst().orElse(0))
                        .build()).toList())
                .build();
    }
}
