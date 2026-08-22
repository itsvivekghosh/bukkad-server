package com.bhukkad.delivery;

import com.bhukkad.config.DeliveryTruthProperties;
import com.bhukkad.entity.DeliveryAgent;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.util.Constants;
import com.bhukkad.util.DistanceCalculator;
import com.bhukkad.zone.DeliveryZoneService;
import com.bhukkad.zone.ZoneSurgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Smarter live ETA using status, GPS, traffic, surge, and restaurant busy mode (V14).
 */
@Service
@RequiredArgsConstructor
public class OrderEtaService {

    private final DeliveryTruthProperties properties;
    private final DeliveryZoneService deliveryZoneService;
    private final ZoneSurgeService zoneSurgeService;
    private final OrderEtaHistoryService etaHistoryService;
    private final RoadDistanceService roadDistanceService;

    public EtaSnapshot computeLiveEta(Order order) {
        Restaurant restaurant = order.getRestaurant();
        int baseDelivery = restaurant.getAverageDeliveryTime() != null
                ? restaurant.getAverageDeliveryTime()
                : Constants.DEFAULT_DELIVERY_TIME;

        int extraPrep = Boolean.TRUE.equals(restaurant.getBusyMode())
                && restaurant.getExtraPrepMinutes() != null
                ? restaurant.getExtraPrepMinutes() : 0;

        double trafficFactor = resolveTrafficFactor();
        double surgeMultiplier = resolveSurgeForOrder(order);

        int minutes = switch (order.getStatus()) {
            case SCHEDULED -> minutesUntil(order.getScheduledAt());
            case PLACED, CONFIRMED -> scaleMinutes(baseDelivery + extraPrep, trafficFactor);
            case PREPARING -> scaleMinutes(Math.max(8, baseDelivery / 2) + extraPrep, trafficFactor);
            case READY_FOR_PICKUP -> properties.getPickupBufferMinutes() + scaleMinutes(8 + extraPrep, trafficFactor);
            case OUT_FOR_DELIVERY -> deliveryMinutes(order, baseDelivery, trafficFactor);
            case DELIVERED -> 0;
            default -> scaleMinutes(baseDelivery, trafficFactor);
        };

        int band = properties.getConfidenceBandMinutes();
        LocalDateTime etaAt = minutes <= 0 ? LocalDateTime.now() : LocalDateTime.now().plusMinutes(minutes);
        String factors = String.format("status=%s,traffic=%.2f,surge=%.2f,prep=%d",
                order.getStatus(), trafficFactor, surgeMultiplier, extraPrep);

        return new EtaSnapshot(minutes, etaAt,
                Math.max(0, minutes - band), minutes + band,
                trafficFactor, surgeMultiplier, factors);
    }

    public void applyLiveEta(Order order) {
        EtaSnapshot snapshot = computeLiveEta(order);
        order.setLiveEtaMinutes(snapshot.minutes());
        order.setLiveEtaAt(snapshot.etaAt());
        if (properties.isRecordSnapshots() && order.getId() != null) {
            etaHistoryService.recordSnapshot(order, snapshot,
                    snapshot.trafficFactor(), snapshot.surgeMultiplier(), snapshot.factorsSummary());
        }
    }

    private int deliveryMinutes(Order order, int fallback, double trafficFactor) {
        DeliveryAgent agent = order.getDeliveryAgent();
        if (agent == null
                || agent.getCurrentLatitude() == null
                || agent.getCurrentLongitude() == null
                || order.getDeliveryAddress() == null
                || order.getDeliveryAddress().getLatitude() == null
                || order.getDeliveryAddress().getLongitude() == null) {
            return scaleMinutes(Math.max(5, fallback / 2), trafficFactor);
        }
        double km;
        RoadDistanceService.RoadRoute route = roadDistanceService.route(
                agent.getCurrentLatitude(), agent.getCurrentLongitude(),
                order.getDeliveryAddress().getLatitude(), order.getDeliveryAddress().getLongitude());
        if (route.fromOsrm()) {
            // Road distance already accounts for real travel time.
            return scaleMinutes(Math.max(5, (int) Math.ceil(route.durationMin())), trafficFactor);
        }
        km = route.distanceKm();
        int travel = (int) Math.ceil(km / properties.getAvgSpeedKmPerMin());
        return scaleMinutes(Math.max(5, travel), trafficFactor);
    }

    /** Peak-hour traffic heuristic: lunch and dinner rush slow delivery. */
    private double resolveTrafficFactor() {
        int hour = LocalDateTime.now().getHour();
        if (hour >= 12 && hour < 14) return 1.15;
        if (hour >= 19 && hour < 22) return 1.25;
        if (hour >= 7 && hour < 10) return 1.1;
        return 1.0;
    }

    private double resolveSurgeForOrder(Order order) {
        if (order.getDeliveryAddress() == null
                || order.getDeliveryAddress().getLatitude() == null
                || order.getDeliveryAddress().getLongitude() == null) {
            return 1.0;
        }
        return deliveryZoneService.findZoneForCoordinates(
                        order.getDeliveryAddress().getLatitude(),
                        order.getDeliveryAddress().getLongitude())
                .map(zoneSurgeService::resolveEffectiveSurge)
                .orElse(1.0);
    }

    private int scaleMinutes(int base, double factor) {
        return (int) Math.ceil(base * factor);
    }

    private int minutesUntil(LocalDateTime target) {
        if (target == null) return 0;
        return (int) Math.max(0, ChronoUnit.MINUTES.between(LocalDateTime.now(), target));
    }

    public record EtaSnapshot(
            int minutes,
            LocalDateTime etaAt,
            int confidenceLowMinutes,
            int confidenceHighMinutes,
            double trafficFactor,
            double surgeMultiplier,
            String factorsSummary) {
    }
}
