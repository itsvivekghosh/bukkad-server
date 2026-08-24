package com.bhukkad.delivery;

import com.bhukkad.entity.Address;
import com.bhukkad.entity.Order;
import com.bhukkad.repository.AddressRepository;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.util.DistanceCalculator;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Orders the stops of a multi-delivery run (TSP approximation) using a
 * nearest-neighbour heuristic over haversine distances, starting from the
 * agent's current location.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RouteOptimizationService {

    private static final Logger log = LoggerFactory.getLogger(RouteOptimizationService.class);

    private final OrderRepository orderRepository;
    private final AddressRepository addressRepository;

    /**
     * Returns the given {@code orderIds} in optimized visit order. Falls back to
     * the original order whenever a stop cannot be located (missing order,
     * missing delivery address or missing coordinates) or no start point is
     * available.
     */
    public List<Long> optimizeStops(List<Long> orderIds, Long agentId, Double currentLat, Double currentLng) {
        if (orderIds == null || orderIds.isEmpty()) {
            return orderIds == null ? List.of() : orderIds;
        }
        if (orderIds.size() == 1) {
            return orderIds;
        }
        if (currentLat == null || currentLng == null) {
            log.warn("Route optimization skipped: missing agent current location for agentId={}", agentId);
            return orderIds;
        }

        List<Stop> stops = new ArrayList<>(orderIds.size());
        for (Long orderId : orderIds) {
            Stop stop = resolveStop(orderId);
            if (stop == null) {
                log.warn("Route optimization skipped: order {} has no usable delivery coordinates", orderId);
                return orderIds;
            }
            stops.add(stop);
        }

        return greedyTspOrder(stops, currentLat, currentLng);
    }

    private Stop resolveStop(Long orderId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null || order.getDeliveryAddress() == null) {
            return null;
        }
        Address address = addressRepository.findById(order.getDeliveryAddress().getId()).orElse(null);
        if (address == null || address.getLatitude() == null || address.getLongitude() == null) {
            return null;
        }
        return new Stop(orderId, address.getLatitude(), address.getLongitude());
    }

    private record Stop(Long orderId, double lat, double lng) {
    }

    /** Applies a nearest-neighbour (greedy) TSP solver to order stops. */
    private List<Long> greedyTspOrder(List<Stop> stops, double startLat, double startLng) {
        List<Long> optimized = new ArrayList<>(stops.size());
        Set<Integer> visited = new HashSet<>();
        double cursorLat = startLat;
        double cursorLng = startLng;

        while (optimized.size() < stops.size()) {
            int next = findNearestUnvisited(stops, visited, cursorLat, cursorLng);
            visited.add(next);
            Stop chosen = stops.get(next);
            optimized.add(chosen.orderId());
            cursorLat = chosen.lat();
            cursorLng = chosen.lng();
        }
        return optimized;
    }

    private int findNearestUnvisited(List<Stop> stops, Set<Integer> visited, double lat, double lng) {
        int nearest = -1;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < stops.size(); i++) {
            if (visited.contains(i)) {
                continue;
            }
            double distance = DistanceCalculator.calculateDistance(lat, lng, stops.get(i).lat(), stops.get(i).lng());
            if (distance < best) {
                best = distance;
                nearest = i;
            }
        }
        return nearest;
    }
}
